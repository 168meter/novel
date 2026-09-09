param(
    [string]$ManagementUrl = 'http://127.0.0.1:8084',
    [string]$PrometheusUrl = 'http://127.0.0.1:9090',
    [string]$GrafanaUrl = 'http://127.0.0.1:3000',
    [string]$GrafanaUser = 'admin',
    [string]$GrafanaPassword = 'admin'
)

$ErrorActionPreference = 'Stop'

function Write-Pass([string]$Message) {
    Write-Host "PASS: $Message" -ForegroundColor Green
}

function Invoke-Json([string]$Uri, [hashtable]$Headers = @{}) {
    try {
        Invoke-RestMethod -Uri $Uri -Headers $Headers -TimeoutSec 10
    }
    catch {
        throw "Request failed: $Uri`n$($_.Exception.Message)"
    }
}

function Invoke-PrometheusQuery([string]$Query) {
    $encoded = [Uri]::EscapeDataString($Query)
    $response = Invoke-Json "$PrometheusUrl/api/v1/query?query=$encoded"
    if ($response.status -ne 'success') {
        throw "Prometheus query failed: $Query"
    }
    return @($response.data.result)
}

$health = Invoke-Json "$ManagementUrl/actuator/health"
if ($health.status -ne 'UP') {
    throw "novel-front health is '$($health.status)', expected UP."
}
Write-Pass 'novel-front Actuator health is UP'

try {
    $null = Invoke-RestMethod -Uri "$PrometheusUrl/-/ready" -TimeoutSec 10
}
catch {
    throw "Prometheus is unavailable: $PrometheusUrl`n$($_.Exception.Message)"
}
Write-Pass 'Prometheus is ready'

$targets = Invoke-Json "$PrometheusUrl/api/v1/targets"
$frontTargets = @($targets.data.activeTargets | Where-Object { $_.labels.job -eq 'novel-front' })
if ($frontTargets.Count -ne 1 -or $frontTargets[0].health -ne 'up') {
    throw 'Prometheus novel-front target is not UP.'
}
Write-Pass 'Prometheus novel-front target is UP'

foreach ($metric in @('novel_chapter_cache_lookup_total', 'novel_book_visit_kafka_send_total')) {
    $series = Invoke-PrometheusQuery $metric
    if ($series.Count -eq 0) {
        throw "Prometheus metric has no series: $metric"
    }
    Write-Pass "Prometheus contains $metric"
}

$rules = Invoke-Json "$PrometheusUrl/api/v1/rules"
$loadedAlerts = @($rules.data.groups.rules | Where-Object { $_.type -eq 'alerting' } | ForEach-Object { $_.name })
$expectedAlerts = @(
    'NovelFrontDown'
    'ChapterCacheErrors'
    'LowChapterCacheHitRate'
    'KafkaPublishFailures'
    'KafkaConsumerLagHigh'
    'HikariPendingConnections'
    'JvmHeapUsageHigh'
)
foreach ($alert in $expectedAlerts) {
    if ($alert -notin $loadedAlerts) {
        throw "Prometheus alert rule is missing: $alert"
    }
}
Write-Pass 'Prometheus loaded all Novel-Plus alert rules'

$basicToken = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${GrafanaUser}:${GrafanaPassword}"))
$grafanaHeaders = @{ Authorization = "Basic $basicToken" }
$grafanaHealth = Invoke-Json "$GrafanaUrl/api/health"
if ($grafanaHealth.database -ne 'ok') {
    throw "Grafana database health is '$($grafanaHealth.database)', expected ok."
}
Write-Pass 'Grafana health is OK'

$datasource = Invoke-Json "$GrafanaUrl/api/datasources/uid/prometheus" $grafanaHeaders
if ($datasource.uid -ne 'prometheus') {
    throw 'Grafana Prometheus datasource is not provisioned.'
}
Write-Pass 'Grafana Prometheus datasource is provisioned'

$dashboard = Invoke-Json "$GrafanaUrl/api/dashboards/uid/novel-plus-overview" $grafanaHeaders
if ($dashboard.dashboard.uid -ne 'novel-plus-overview') {
    throw 'Grafana Novel-Plus dashboard is not provisioned.'
}
Write-Pass 'Grafana Novel-Plus dashboard is provisioned'

Write-Host 'Observability runtime verification passed.' -ForegroundColor Green
