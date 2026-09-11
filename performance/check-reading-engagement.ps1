param(
    [Parameter(Mandatory = $true)][ValidateRange(1, [long]::MaxValue)][long]$BookId,
    [ValidateRange(0, [long]::MaxValue)][long]$ChapterId = 0,
    [string]$BaseUrl = 'http://127.0.0.1:8083',
    [string]$ManagementUrl = 'http://127.0.0.1:8084',
    [switch]$VerifyIpLimit,
    [ValidateSet('None', 'Redis', 'Kafka')][string]$FailureDrill = 'None'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$ManagementUrl = $ManagementUrl.TrimEnd('/')
if ($VerifyIpLimit -and $FailureDrill -ne 'None') {
    throw 'Run the IP limit and dependency failure drills separately.'
}

# Database access is SELECT-only. No Redis enumeration or deletion is performed.
if ($ChapterId -eq 0) {
    $rawChapterId = docker exec -e MYSQL_PWD=123456 novel-mysql `
        mysql -uroot -N -s novel_plus -e `
        "SELECT id FROM book_index WHERE book_id = $BookId AND COALESCE(is_vip, 0) = 0 ORDER BY index_num LIMIT 1;" 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $rawChapterId) {
        throw "No readable non-VIP chapter found for book $BookId (local MySQL SELECT unavailable or empty)."
    }
    $ChapterId = [long]($rawChapterId | Select-Object -Last 1)
}

function Get-Counter([string]$Name, [string]$Result = '', [int]$TimeoutSec = 5) {
    $uri = "$ManagementUrl/actuator/metrics/$Name"
    if ($Result) { $uri += '?tag=result:' + $Result }
    $metric = Invoke-RestMethod -Uri $uri -TimeoutSec $TimeoutSec
    $count = @($metric.measurements | Where-Object { $_.statistic -eq 'COUNT' })
    if ($count.Count -ne 1) { throw "Missing COUNT measurement: $Name / $Result" }
    return [double]$count[0].value
}

function Get-ReadingCounters {
    return @{
        accepted = Get-Counter 'novel.reading.heartbeat' 'accepted'
        duplicate = Get-Counter 'novel.reading.heartbeat' 'duplicate'
        credited = Get-Counter 'novel.reading.credited.seconds'
        kafka = Get-Counter 'novel.reading.kafka.send' 'success'
        limited = Get-Counter 'novel.reading.heartbeat' 'ip_rate_limited'
        failed = Get-Counter 'novel.reading.kafka.send' 'failed'
    }
}

function Open-ReadingPage($Session, [hashtable]$Headers = @{}, [switch]$ExpectNoToken) {
    # Catch without forwarding response bodies, headers, Cookie, or page tokens.
    try {
        $page = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/book/$BookId/$ChapterId.html" `
            -WebSession $Session -Headers $Headers -TimeoutSec 30
    }
    catch { throw 'Chapter GET failed; response details withheld to protect browser identity.' }
    if ([int]$page.StatusCode -ne 200) { throw 'Chapter GET did not return HTTP 200.' }
    $match = [regex]::Match($page.Content, 'data-page-visit-id\s*=\s*["'']([0-9a-f]{32})["'']')
    if ($ExpectNoToken) {
        if ($match.Success -or $page.Content -match 'data-page-visit-id\s*=\s*["''][^"'']+|readingPageVisitId') {
            throw 'Redis failure drill: a reading page token was unexpectedly rendered.'
        }
        # A custom HTTP-200 error page must not count as a successful chapter fallback.
        if ($page.Content -notmatch 'id\s*=\s*["''](?:showReading|chaptercontent)["'']') {
            throw 'Redis failure drill returned HTTP 200 but no chapter content element.'
        }
        return
    }
    if (-not $match.Success) { throw 'Rendered chapter has no 32-hex data-page-visit-id; verify chapter, secret, and Redis.' }
    if (-not $Session.Cookies.GetCookies([uri]$BaseUrl)['userClientMarkKey']) {
        throw 'Chapter response did not establish userClientMarkKey in the WebRequestSession.'
    }
    return $match.Groups[1].Value
}

function Send-Heartbeat($Session, [string]$PageVisitId, [long]$Sequence, [hashtable]$Headers = @{}) {
    $body = @{ bookId = $BookId; chapterId = $ChapterId; pageVisitId = $PageVisitId; sequence = $Sequence } | ConvertTo-Json -Compress
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/engagement/reading/heartbeat" `
            -Method Post -ContentType 'application/json' -Body $body -WebSession $Session -Headers $Headers -TimeoutSec 30
    }
    catch { throw 'Heartbeat POST failed; response details withheld to protect browser identity.' }
    if ([int]$response.StatusCode -ne 200) { throw 'Heartbeat POST did not return HTTP 200.' }
}

function Assert-Deltas([hashtable]$Before, [hashtable]$Expected) {
    # Poll for asynchronous Kafka acknowledgement for at most ten seconds in total.
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $deltas = @{}
    do {
        $complete = $true
        foreach ($key in $Expected.Keys) {
            $remaining = 10 - $timer.Elapsed.TotalSeconds
            if ($remaining -lt 1) { $complete = $false; break }
            $name = 'novel.reading.heartbeat'; $result = $key
            if ($key -eq 'credited') { $name = 'novel.reading.credited.seconds'; $result = '' }
            elseif ($key -eq 'kafka') { $name = 'novel.reading.kafka.send'; $result = 'success' }
            elseif ($key -eq 'limited') { $result = 'ip_rate_limited' }
            $deltas[$key] = (Get-Counter $name $result ([int][Math]::Floor($remaining))) - $Before[$key]
            if ($deltas[$key] -gt $Expected[$key]) { throw "Unexpected concurrent traffic: $key delta=$($deltas[$key]), expected=$($Expected[$key])." }
            if ($deltas[$key] -ne $Expected[$key]) { $complete = $false }
        }
        if ($complete) {
            $summary = ($Expected.Keys | Sort-Object | ForEach-Object { "$_=$($deltas[$_])" }) -join ', '
            Write-Host "PASS: HTTP responses successful; metric deltas $summary."
            return
        }
        if ($timer.Elapsed.TotalSeconds -lt 9.7) { Start-Sleep -Milliseconds 200 }
    } while ($timer.Elapsed.TotalSeconds -lt 9)
    $summary = ($Expected.Keys | Sort-Object | ForEach-Object { "$_=$($deltas[$_]) (expected $($Expected[$_]))" }) -join ', '
    throw "Metric deltas not observed within 10 seconds: $summary"
}

function Get-DependencyState([string]$Container) {
    $state = docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' $Container 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect local dependency $Container; no stop attempted." }
    return "$state".Trim()
}

function Restore-Dependency([string]$Container) {
    docker start $Container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "CRITICAL: could not restart $Container; run docker start $Container immediately." }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-DependencyState $Container
        if ($state -eq 'running healthy') { Write-Host "RESTORED: $Container running healthy."; return }
        Start-Sleep -Seconds 2
    } while ($timer.Elapsed.TotalSeconds -lt 120)
    throw "Dependency restarted but not healthy within 120 seconds: $Container ($state)."
}

if ($FailureDrill -ne 'None') {
    # Preflight both dependencies before entering a bounded, single-dependency drill.
    foreach ($dependency in @('novel-redis', 'novel-kafka')) {
        if ((Get-DependencyState $dependency) -ne 'running healthy') {
            throw "Failure drill requires a healthy dependency: $dependency"
        }
    }
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $null = Open-ReadingPage $session
    $container = if ($FailureDrill -eq 'Redis') { 'novel-redis' } else { 'novel-kafka' }
    try {
        docker stop --time 10 $container | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not stop $container." }
        if ($FailureDrill -eq 'Redis') {
            Open-ReadingPage $session -ExpectNoToken
            Write-Host 'PASS: Redis down, chapter HTTP 200 with chapter content and no reading page token.'
        }
        else {
            $token = Open-ReadingPage $session
            $before = Get-Counter 'novel.reading.kafka.send' 'failed'
            Send-Heartbeat $session $token 1
            Send-Heartbeat $session $token 2
            # Kafka failure callbacks follow delivery.timeout.ms; allow a bounded 150 seconds.
            $timer = [Diagnostics.Stopwatch]::StartNew()
            $delta = 0
            do {
                $delta = (Get-Counter 'novel.reading.kafka.send' 'failed') - $before
                if ($delta -ge 2) { break }
                Start-Sleep -Seconds 2
            } while ($timer.Elapsed.TotalSeconds -lt 150)
            if ($delta -ne 2) { throw "Kafka failure metric delta=$delta, expected=2 within 150 seconds." }
            Write-Host 'PASS: Kafka down, both heartbeats HTTP 200; Kafka failed delta=2.'
        }
    }
    finally { Restore-Dependency $container }
    return
}

if ($VerifyIpLimit) {
    # Use a quiet local app and wait at least 61 seconds since any previous IP drill.
    $headers = @{ 'X-Real-IP' = '198.51.100.77' }
    $before = Get-ReadingCounters
    $timer = [Diagnostics.Stopwatch]::StartNew()
    for ($i = 0; $i -lt 121; $i++) {
        if ($timer.Elapsed.TotalSeconds -ge 60) { throw 'IP drill exceeded the 60-second rate window; retry on an idle local instance.' }
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $token = Open-ReadingPage $session $headers
        Send-Heartbeat $session $token 1 $headers
    }
    if ($timer.Elapsed.TotalSeconds -ge 60) { throw 'IP drill exceeded the 60-second rate window; retry on an idle local instance.' }
    Assert-Deltas $before @{ accepted = 120; limited = 1 }
    Write-Host 'PASS: IP drill used 121 independent sessions; accepted=120, ip_rate_limited=1; no Redis cleanup.'
}
else {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $token = Open-ReadingPage $session
    $before = Get-ReadingCounters
    Send-Heartbeat $session $token 1
    Send-Heartbeat $session $token 1
    Send-Heartbeat $session $token 2
    Assert-Deltas $before @{ accepted = 2; duplicate = 1; credited = 60; kafka = 2 }
}
