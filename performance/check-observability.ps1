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
    # Every runtime assertion must use the currently validated application target.
    $instance = $frontTargets[0].labels.instance.Replace('\', '\\').Replace('"', '\"')
    $scope = 'instance="' + $instance + '"'
    if ($Query -notmatch 'job=') { $scope += ',job="novel-front"' }
    if ($Query.Contains('{')) { $Query = $Query.Replace('{', '{' + $scope + ',') }
    else { $Query += '{' + $scope + '}' }
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

foreach ($metric in @(
    'novel_chapter_cache_lookup_total', 'novel_book_visit_kafka_send_total',
    'novel_reading_kafka_persisted_seconds_total', 'novel_reading_kafka_deduplicated_total',
    'novel_reading_kafka_daily_rows_updated_total',
    'novel_reading_kafka_dlt_total', 'novel_reading_kafka_dlt_publish_failures_total',
    'novel_reading_dedup_cleanup_deleted_total', 'novel_reading_dedup_cleanup_failures_total',
    'novel_home_recommendation_local_age_seconds',
    'novel_home_recommendation_refresh_total',
    'novel_home_recommendation_generation_seconds_count',
    'novel_home_recommendation_source_total',
    'novel_auth_captcha_request_total', 'novel_auth_captcha_verify_total',
    'novel_auth_mail_delivery_total', 'novel_auth_login_total',
    'novel_auth_password_upgrade_total', 'novel_auth_jwt_version_total',
    'novel_auth_argon2_duration_seconds_count'
)) {
    $series = Invoke-PrometheusQuery $metric
    if ($series.Count -eq 0) {
        throw "Prometheus metric has no series: $metric"
    }
    Write-Pass "Prometheus contains $metric"
}

# Failure counters now have a startup zero baseline; absence is a monitoring failure.
foreach ($metric in @('novel_reading_kafka_retry_total', 'novel_book_visit_kafka_retry_total',
    'novel_book_visit_kafka_dlt_total', 'novel_chapter_request_rejected_total')) {
    if (@(Invoke-PrometheusQuery $metric).Count -eq 0) { throw "Missing startup counter: $metric" }
}
foreach ($query in @(
    'kafka_consumer_connection_count{job="novel-front",spring_id=~"readingEngagementConsumerFactory[.].*"}',
    'kafka_consumer_fetch_manager_records_lag{job="novel-front",topic="novel-reading-engagement-v1"}'
)) {
    if (@(Invoke-PrometheusQuery $query).Count -eq 0) { throw "Missing reading client metric: $query" }
}

$executorQueries = @(
    'executor_active_threads{name="novel.front.executor"}'
    'executor_pool_size_threads{name="novel.front.executor"}'
    'executor_completed_tasks_total{name="novel.front.executor"}'
    'executor_queued_tasks{name="novel.front.executor"}'
    'executor_queue_remaining_tasks{name="novel.front.executor"}'
    'novel_front_executor_rejections_total'
)
foreach ($query in $executorQueries) {
    $series = Invoke-PrometheusQuery $query
    if ($series.Count -eq 0) {
        throw "Prometheus executor query has no series: $query"
    }
    Write-Pass "Prometheus query returned executor data: $query"
}

$rules = Invoke-Json "$PrometheusUrl/api/v1/rules"
$loadedAlerts = @($rules.data.groups.rules | Where-Object { $_.type -eq 'alerting' } | ForEach-Object { $_.name })
$expectedAlerts = @(
    'NovelFrontDown'
    'ChapterHttp5xxHigh'
    'ChapterHttp503Observed'
    'ReadingConsumerMetricsMissing'
    'ChapterCacheErrors'
    'LowChapterCacheHitRate'
    'KafkaPublishFailures'
    'KafkaConsumerLagHigh'
    'HikariPendingConnections'
    'JvmHeapUsageHigh'
    'FrontExecutorRejectedTasks'
    'FrontExecutorQueueSaturated'
    'ReadingEngagementRedisErrors'
    'ReadingEngagementKafkaPublishFailures'
    'ReadingConsumerLagHigh'
    'ReadingConsumerDltGrowth'
    'ReadingConsumerRetries'
    'ReadingConsumerDltPublishFailures'
    'ReadingDedupCleanupFailures'
    'HomeRecommendationRefreshDbErrors'
    'HomeRecommendationSnapshotStale'
    'AuthenticationMailDeliveryFailures'
    'AuthenticationMailQueueRejections'
    'AuthenticationDependencyErrors'
    'AuthenticationLoginFailuresHigh'
    'AuthenticationPasswordUpgradeFailures'
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
$corePanels = @($dashboard.dashboard.panels | Where-Object { $_.type -ne 'row' })
if ($corePanels.Count -ne 12) { throw 'Grafana Benchmark Overview must have exactly 12 core panels.' }
$tracked = Get-Content (Join-Path $PSScriptRoot '../monitoring/grafana/dashboards/novel-plus-overview.json') -Raw | ConvertFrom-Json
foreach ($expected in $tracked.panels) {
    $actual = @($corePanels | Where-Object { $_.id -eq $expected.id })
    if ($actual.Count -ne 1 -or $actual[0].title -ne $expected.title -or
        ($actual[0].targets.expr -join "`n") -ne ($expected.targets.expr -join "`n")) {
        throw "Grafana provisioned panel/query differs from tracked definition: $($expected.id)"
    }
}
$dsHealth = Invoke-Json "$GrafanaUrl/api/datasources/uid/prometheus/health" $grafanaHeaders
if ($dsHealth.status -ne 'OK') { throw 'Grafana cannot query Prometheus.' }
Write-Pass 'Grafana Novel-Plus dashboard is provisioned'

Write-Host 'Observability runtime verification passed.' -ForegroundColor Green
