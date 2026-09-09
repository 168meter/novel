[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [long]$BookId,

    [ValidateRange(1, 1000000)]
    [int]$RequestCount = 1000,

    [ValidateRange(1, 500)]
    [int]$MaxConcurrency = 100,

    [string]$BaseUrl = 'http://127.0.0.1:8083',

    [string]$ActuatorUrl = 'http://127.0.0.1:8084',

    [string]$ConsumerGroup = 'novel-book-visit-writer-v1'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function Get-BookVisitCount {
    param([long]$TargetBookId)

    $raw = docker exec -e MYSQL_PWD=123456 novel-mysql mysql -uroot -N -s novel_plus -e "SELECT visit_count FROM book WHERE id = $TargetBookId;" 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $raw) {
        throw "Unable to read visit_count for book $TargetBookId"
    }
    return [long]($raw | Select-Object -Last 1)
}

function Get-MetricCount {
    param(
        [string]$MetricName,
        [string]$Result
    )

    $encodedName = [Uri]::EscapeDataString($MetricName)
    $uri = "$ActuatorUrl/actuator/metrics/$encodedName"
    if ($Result) {
        $encodedTag = [Uri]::EscapeDataString("result:$Result")
        $uri = "${uri}?tag=$encodedTag"
    }

    try {
        $metric = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 5
    }
    catch {
        return 0.0
    }

    $measurement = $metric.measurements |
        Where-Object { $_.statistic -in @('COUNT', 'VALUE') } |
        Select-Object -First 1
    if ($null -eq $measurement) {
        return 0.0
    }
    return [double]$measurement.value
}

function Get-VisitConsumerLag {
    $lines = docker exec novel-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group $ConsumerGroup 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to describe Kafka consumer group $ConsumerGroup"
    }

    [long]$totalLag = 0
    [int]$partitionRows = 0
    foreach ($line in $lines) {
        $columns = ($line.Trim() -split '\s+')
        if ($columns.Length -ge 6 -and $columns[0] -eq $ConsumerGroup) {
            [long]$lag = 0
            if ([long]::TryParse($columns[5], [ref]$lag)) {
                $totalLag += $lag
                $partitionRows++
            }
        }
    }
    if ($partitionRows -eq 0) {
        throw "No partition lag rows found for consumer group $ConsumerGroup"
    }
    return $totalLag
}

$health = Invoke-RestMethod -Uri "$ActuatorUrl/actuator/health" -Method Get -TimeoutSec 5
if ($health.status -ne 'UP') {
    throw "Application health is $($health.status), expected UP"
}

$initialCount = Get-BookVisitCount -TargetBookId $BookId
$successBefore = Get-MetricCount -MetricName 'novel.book.visit.kafka.send' -Result 'success'
$failedBefore = Get-MetricCount -MetricName 'novel.book.visit.kafka.send' -Result 'failed'

[int]$acceptedRequests = 0
$httpHandler = [System.Net.Http.HttpClientHandler]::new()
$httpHandler.MaxConnectionsPerServer = $MaxConcurrency
$httpClient = [System.Net.Http.HttpClient]::new($httpHandler, $true)
$httpClient.Timeout = [TimeSpan]::FromSeconds(10)
try {
    for ($batchStart = 0; $batchStart -lt $RequestCount; $batchStart += $MaxConcurrency) {
        $batchCount = [Math]::Min($MaxConcurrency, $RequestCount - $batchStart)
        $requests = @()
        for ($requestNumber = 0; $requestNumber -lt $batchCount; $requestNumber++) {
            $content = [System.Net.Http.StringContent]::new(
                "bookId=$BookId",
                [System.Text.Encoding]::UTF8,
                'application/x-www-form-urlencoded'
            )
            $requests += [pscustomobject]@{
                Content = $content
                Task = $httpClient.PostAsync("$BaseUrl/book/addVisitCount", $content)
            }
        }
        foreach ($request in $requests) {
            try {
                $response = $request.Task.GetAwaiter().GetResult()
                $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                if ($response.IsSuccessStatusCode -and $body.ok -eq $true) {
                    $acceptedRequests++
                }
                $response.Dispose()
            }
            finally {
                $request.Content.Dispose()
            }
        }
    }
}
finally {
    $httpClient.Dispose()
}

$drainTimer = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    Start-Sleep -Milliseconds 500
    $successAfter = Get-MetricCount -MetricName 'novel.book.visit.kafka.send' -Result 'success'
    $failedAfter = Get-MetricCount -MetricName 'novel.book.visit.kafka.send' -Result 'failed'
    $producerSuccesses = [long][Math]::Round($successAfter - $successBefore)
    $producerFailures = [long][Math]::Round($failedAfter - $failedBefore)
    $confirmedSends = $producerSuccesses + $producerFailures
    $currentCount = Get-BookVisitCount -TargetBookId $BookId
    $lag = Get-VisitConsumerLag
    $expectedCount = $initialCount + $producerSuccesses
} while (
    ($confirmedSends -lt $acceptedRequests -or $lag -gt 0 -or $currentCount -lt $expectedCount) -and
    [DateTime]::UtcNow -lt $deadline
)
$drainTimer.Stop()

$finalCount = Get-BookVisitCount -TargetBookId $BookId
$finalDelta = $finalCount - $initialCount

$result = [pscustomobject]@{
    BookId = $BookId
    InitialCount = $initialCount
    Requested = $RequestCount
    MaxConcurrency = $MaxConcurrency
    HttpAccepted = $acceptedRequests
    ProducerSuccesses = $producerSuccesses
    ProducerFailures = $producerFailures
    FinalCount = $finalCount
    FinalDelta = $finalDelta
    ConsumerLag = $lag
    DrainMilliseconds = $drainTimer.ElapsedMilliseconds
}

if ($acceptedRequests -ne $RequestCount) {
    throw "Only $acceptedRequests of $RequestCount HTTP requests were accepted"
}
if ($confirmedSends -lt $acceptedRequests) {
    throw "Only $confirmedSends of $acceptedRequests accepted requests reached a producer result within 60 seconds"
}
if ($lag -gt 0) {
    throw "Kafka lag did not drain within 60 seconds: $lag"
}
if ($finalDelta -ne $producerSuccesses) {
    throw "Database delta $finalDelta does not match producer-confirmed successes $producerSuccesses"
}

return $result
