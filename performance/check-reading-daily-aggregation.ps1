param(
    [Parameter(Mandatory = $true)][ValidateRange(1, [long]::MaxValue)][long]$BookId,
    [ValidateSet('None', 'Dlt', 'MySql')][string]$FailureDrill = 'None',
    [string]$ManagementUrl = 'http://127.0.0.1:8084',
    [ValidateRange(60, 240)][int]$RetryWaitSeconds = 180
)
$ErrorActionPreference = 'Stop'
$topic = 'novel-reading-engagement-v1'
$dltTopic = 'novel-reading-engagement-dlt'
$group = 'novel-reading-engagement-writer-v1'
$eventIds = @()
$published = $false
$drained = $false

function Invoke-LocalDocker([string[]]$Arguments, [string]$Payload = '') {
    $oldPreference = $ErrorActionPreference
    $oldEncoding = $OutputEncoding
    try {
        $ErrorActionPreference = 'Continue'
        $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
        if ($Payload) { $lines = $Payload | docker @Arguments 2>$null }
        else { $lines = docker @Arguments 2>$null }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
        $OutputEncoding = $oldEncoding
    }
    if ($code -ne 0) { throw 'Local Docker operation failed; details withheld.' }
    return @($lines)
}
function Invoke-Sql([string]$Sql) {
    Invoke-LocalDocker @('exec', '-e', 'MYSQL_PWD=123456', 'novel-mysql', 'mysql', '-uroot', '-N', '-s', 'novel_plus', '-e', $Sql)
}
function Wait-Until([scriptblock]$Check, [string]$Description, [int]$Seconds = 60) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (& $Check) { return }
        Start-Sleep -Milliseconds 500
    } while ($timer.Elapsed.TotalSeconds -lt $Seconds)
    throw "Timed out: $Description"
}
function Get-Lag {
    $lines = Invoke-LocalDocker @('exec', 'novel-kafka', '/opt/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:9092', '--describe', '--group', $group)
    $rows = 0; [long]$total = 0
    foreach ($line in $lines) {
        $columns = $line.Trim() -split '\s+'
        if ($columns.Count -ge 6 -and $columns[0] -eq $group -and $columns[1] -eq $topic) {
            [long]$lag = 0
            if (-not [long]::TryParse($columns[5], [ref]$lag)) { throw 'Reading consumer offset is not initialized.' }
            $rows++; $total += $lag
        }
    }
    if ($rows -eq 0) { throw 'No reading consumer partition rows; start the monitoring frontend.' }
    return $total
}
function Get-Retry {
    # This counter is absent before its first retry. Other errors must not become zero.
    try {
        $metric = Invoke-RestMethod "$ManagementUrl/actuator/metrics/novel.reading.kafka.retry" -TimeoutSec 5
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { return 0 }
        throw
    }
    return [double](@($metric.measurements | Where-Object { $_.statistic -eq 'COUNT' })[0].value)
}
function New-TestBook {
    # Eight ASCII digits are exactly eight bytes: a valid LongDeserializer key.
    # The resulting Long is also the payload book ID, not a string-serialized key.
    $key = (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
    $bytes = [Text.Encoding]::UTF8.GetBytes($key)
    [array]::Reverse($bytes)
    return @{ key = $key; id = [BitConverter]::ToInt64($bytes, 0) }
}
function New-Event($Book, [string]$Date, [int]$Version = 1) {
    $id = [guid]::NewGuid().ToString()
    $script:eventIds += $id
    return @{ key = $Book.key; body = [ordered]@{
        eventId = $id; bookId = $Book.id; chapterId = $chapterId
        creditedSeconds = 30; occurredAt = "${Date}T04:00:00Z"; statDate = $Date; version = $Version
    } }
}
function Publish($Event) {
    $script:published = $true
    $json = $Event.body | ConvertTo-Json -Compress
    $null = Invoke-LocalDocker @('exec', '-i', 'novel-kafka', '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'localhost:9092', '--topic', $topic,
        '--property', 'parse.key=true', '--property', 'key.separator=|', '--producer-property', 'acks=all') ($Event.key + '|' + $json)
}
function Assert-Row($Book, [string]$Date, [long]$Seconds, [long]$Count) {
    $row = @(Invoke-Sql "SELECT credited_seconds,heartbeat_count FROM book_reading_daily WHERE book_id=$($Book.id) AND stat_date='$Date';")
    if ($row.Count -eq 0) { return $false }
    $values = $row[0] -split '\s+'
    if ([long]$values[0] -gt $Seconds -or [long]$values[1] -gt $Count) { throw 'Unexpected extra reading credit.' }
    return ([long]$values[0] -eq $Seconds -and [long]$values[1] -eq $Count)
}
function Get-DltOffsets {
    $result = @{}
    foreach ($line in (Invoke-LocalDocker @('exec', 'novel-kafka', '/opt/kafka/bin/kafka-get-offsets.sh', '--bootstrap-server', 'localhost:9092', '--topic', $dltTopic))) {
        if ($line -match '^novel-reading-engagement-dlt:(\d+):(\d+)$') { $result[$Matches[1]] = [long]$Matches[2] }
    }
    if ($result.Count -eq 0) { throw 'Reading DLT offsets unavailable.' }
    return $result
}

$health = Invoke-RestMethod "$ManagementUrl/actuator/health" -TimeoutSec 5
if ($health.status -ne 'UP') { throw 'Frontend health must be UP.' }
$chapterId = @(Invoke-Sql "SELECT id FROM book_index WHERE book_id=$BookId ORDER BY index_num LIMIT 1;")
if ($chapterId.Count -ne 1) { throw 'BookId must identify a local book with a chapter.' }
$chapterId = [long]$chapterId[0]
$a = New-TestBook; $b = New-TestBook
if ($a.id -eq $b.id) { throw 'Test ID collision; rerun.' }
$date1 = '2000-01-01'; $date2 = '2000-01-02'
foreach ($book in @($a, $b)) {
    $occupied = @(Invoke-Sql "SELECT (SELECT COUNT(*) FROM book WHERE id=$($book.id))+(SELECT COUNT(*) FROM book_reading_daily WHERE book_id=$($book.id));")
    if ($occupied.Count -ne 1 -or [long]$occupied[0] -ne 0) { throw 'Test ID already exists; refusing to overwrite existing rows.' }
}
Wait-Until { (Get-Lag) -eq 0 } 'initial reading consumer drain'
try {
    $first = New-Event $a $date1
    if ($FailureDrill -eq 'MySql') {
        $mysqlHealth = Invoke-LocalDocker @('inspect', '--format', '{{.State.Health.Status}}', 'novel-mysql')
        if (($mysqlHealth -join '').Trim() -ne 'healthy') { throw 'MySQL drill requires a healthy local container.' }
        $retryBefore = Get-Retry
        try {
            $null = Invoke-LocalDocker @('stop', 'novel-mysql')
            Publish $first
            # failedDelivery(attempt > 1) runs only AFTER a retry also fails.
            # Allow initial DB failure/rollback plus a second failed delivery.
            Wait-Until { (Get-Retry) -gt $retryBefore } 'MySQL failed-retry metric growth' $RetryWaitSeconds
        } finally {
            $null = Invoke-LocalDocker @('start', 'novel-mysql')
            Wait-Until { ((Invoke-LocalDocker @('inspect', '--format', '{{.State.Health.Status}}', 'novel-mysql')) -join '').Trim() -eq 'healthy' } 'MySQL health restoration' 90
            Write-Host 'RESTORED: novel-mysql healthy.'
        }
    } else { Publish $first }
    Wait-Until { (Assert-Row $a $date1 30 1) -and (Get-Lag) -eq 0 } 'first 30-second credit'
    Publish $first
    Wait-Until { (Get-Lag) -eq 0 } 'duplicate replay drain'
    if (-not (Assert-Row $a $date1 30 1)) { throw 'Duplicate changed the reading row.' }
    Publish (New-Event $a $date2)
    Publish (New-Event $b $date1)
    Wait-Until { (Assert-Row $a $date2 30 1) -and (Assert-Row $b $date1 30 1) -and (Get-Lag) -eq 0 } 'book/date split'
    if ($FailureDrill -eq 'Dlt') {
        $before = Get-DltOffsets
        $bad = New-Event $a $date1 99
        Publish $bad
        Publish (New-Event $a $date1)
        Wait-Until { (Assert-Row $a $date1 60 2) -and (Get-Lag) -eq 0 } 'later valid event after DLT'
        $after = Get-DltOffsets
        [long]$delta = 0; $partition = $null
        foreach ($part in $before.Keys) {
            $growth = $after[$part] - $before[$part]
            $delta += $growth
            if ($growth -eq 1) { $partition = $part }
        }
        if ($delta -ne 1 -or $null -eq $partition) { throw 'Expected exactly one reading DLT record; avoid concurrent traffic.' }
        $record = Invoke-LocalDocker @('exec', 'novel-kafka', '/opt/kafka/bin/kafka-console-consumer.sh', '--bootstrap-server', 'localhost:9092', '--topic', $dltTopic,
            '--partition', $partition, '--offset', $before[$partition].ToString(), '--max-messages', '1', '--timeout-ms', '10000')
        $decoded = ($record -join '') | ConvertFrom-Json
        if ($decoded.eventId -ne $bad.body.eventId) { throw 'Reading DLT contained the wrong event ID.' }
    }
    $drained = $true
    Write-Host "PASS: first credit=30/1; replay delta=0; book/date split; reading lag=0; drill=$FailureDrill."
} finally {
    # Never remove dedup protection while an unacknowledged record could replay.
    if ($published -and $drained) {
        $ids = ($eventIds | ForEach-Object { "'$_'" }) -join ','
        $null = Invoke-Sql "START TRANSACTION; DELETE FROM reading_event_dedup WHERE event_id IN ($ids); DELETE FROM book_reading_daily WHERE book_id IN ($($a.id),$($b.id)) AND stat_date IN ('$date1','$date2'); COMMIT;"
        Write-Host 'CLEANED: only this run event IDs and isolated book/date rows.'
    } elseif ($published) {
        Write-Host "RETAINED: test evidence for book IDs $($a.id),$($b.id); cleanup skipped because acceptance did not finish."
    }
}
