$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot 'check-reading-daily-aggregation.ps1'
$priorExitCode = $global:LASTEXITCODE
function Invoke-RestMethod {
    if ($args[0] -like '*/actuator/health') { return @{ status = 'UP' } }
    return @{ measurements = @(@{ statistic = 'COUNT'; value = $global:readingDailyTest.retry }) }
}
function docker {
    $arguments = @($args); $global:LASTEXITCODE = 0
    $state = $global:readingDailyTest
    if ($arguments[0] -eq 'inspect') { return 'healthy' }
    if ($arguments[0] -eq 'stop') {
        if (($arguments -join ' ') -ne 'stop novel-mysql') { throw 'Wrong stopped container.' }
        $state.stopped++; return
    }
    if ($arguments[0] -eq 'start') { $state.started++; return }
    $tool = $arguments[2]
    if ($arguments -contains 'mysql') {
        $sql = $arguments[-1]
        if ($sql -like 'SELECT id FROM book_index*') { return '17' }
        if ($sql -like 'SELECT (SELECT COUNT(*)*') {
            if ($state.fault -eq 'Occupied') { return '1' }
            return '0'
        }
        if ($sql -match '^SELECT credited_seconds,heartbeat_count.*book_id=(\d+) AND stat_date=''([^'']+)''') {
            $key = $Matches[1] + '/' + $Matches[2]
            if ($state.rows.ContainsKey($key)) {
                $row = $state.rows[$key]; return "$($row.seconds)`t$($row.count)"
            }
            return
        }
        if ($sql -like 'START TRANSACTION; DELETE*') {
            foreach ($id in $state.events.Keys) {
                if (-not $sql.Contains("'$id'")) { throw 'Cleanup omitted generated event ID.' }
            }
            if ($sql -notmatch "WHERE book_id IN \(\d+,\d+\) AND stat_date IN \('2000-01-01','2000-01-02'\)") { throw 'Unbounded daily cleanup.' }
            $state.cleaned++; return
        }
        throw "Unhandled test SQL: $sql"
    }
    if ($tool -like '*kafka-consumer-groups.sh') {
        return 'novel-reading-engagement-writer-v1 novel-reading-engagement-v1 0 10 10 0 consumer host client'
    }
    if ($tool -like '*kafka-get-offsets.sh') { return "novel-reading-engagement-dlt:0:$($state.bad.Count)" }
    if ($tool -like '*kafka-console-consumer.sh') { return $state.bad[0] | ConvertTo-Json -Compress }
    if ($arguments -contains '/opt/kafka/bin/kafka-console-producer.sh') {
        if ($state.fault -eq 'Publish') { throw 'Injected producer failure.' }
        $payload = ($input | Out-String).Trim()
        $parts = $payload -split '\|', 2
        if ($parts[0].Length -ne 8) { throw 'Kafka key must be eight bytes.' }
        $bytes = [Text.Encoding]::UTF8.GetBytes($parts[0]); [array]::Reverse($bytes)
        $event = $parts[1] | ConvertFrom-Json
        if ([BitConverter]::ToInt64($bytes, 0) -ne $event.bookId) { throw 'Kafka Long key and book ID mismatch.' }
        if ($state.stopped -gt $state.started) { $state.retry++ }
        if ($state.events.ContainsKey($event.eventId)) { $state.duplicates++; return }
        $state.events[$event.eventId] = $event
        if ($event.version -ne 1) { $state.bad += $event; return }
        $key = $event.bookId.ToString() + '/' + $event.statDate
        if (-not $state.rows.ContainsKey($key)) { $state.rows[$key] = @{ seconds = 0; count = 0 } }
        $state.rows[$key].seconds += 30; $state.rows[$key].count++
        return
    }
    throw 'Unhandled Docker test command.'
}
try {
    foreach ($drill in @('None', 'Dlt', 'MySql')) {
        $global:readingDailyTest = @{ rows = @{}; events = @{}; bad = @(); duplicates = 0; stopped = 0; started = 0; retry = 0; cleaned = 0 }
        & $path -BookId 1 -FailureDrill $drill
        $state = $global:readingDailyTest
        if ($state.rows.Count -ne 3 -or $state.duplicates -ne 1 -or $state.cleaned -ne 1) { throw 'Ordinary acceptance or cleanup missing.' }
        if ($drill -eq 'Dlt' -and ($state.bad.Count -ne 1 -or ($state.rows.Values.seconds | Measure-Object -Sum).Sum -ne 120)) { throw 'DLT acceptance missing.' }
        if ($drill -eq 'MySql' -and ($state.stopped -ne 1 -or $state.started -ne 1 -or $state.retry -ne 1)) { throw 'MySQL restoration/retry missing.' }
    }
    foreach ($fault in @('Occupied', 'Publish')) {
        $global:readingDailyTest = @{ rows = @{}; events = @{}; bad = @(); duplicates = 0; stopped = 0; started = 0; retry = 0; cleaned = 0; fault = $fault }
        $failed = $false
        try { & $path -BookId 1 -FailureDrill MySql } catch { $failed = $true }
        if (-not $failed -or $global:readingDailyTest.cleaned -ne 0) { throw 'Failure must not clean replay protection.' }
        if ($fault -eq 'Occupied' -and $global:readingDailyTest.stopped -ne 0) { throw 'Existing test ID must stop before the drill.' }
        if ($fault -eq 'Publish' -and ($global:readingDailyTest.stopped -ne 1 -or $global:readingDailyTest.started -ne 1)) { throw 'Producer failure must still restore MySQL.' }
    }
    Write-Host 'Reading daily script orchestration passed (None, Dlt, MySql; simulated external boundaries only).'
} finally {
    Remove-Item Function:docker,Function:Invoke-RestMethod
    Remove-Variable readingDailyTest -Scope Global -ErrorAction SilentlyContinue
    $global:LASTEXITCODE = $priorExitCode
}
