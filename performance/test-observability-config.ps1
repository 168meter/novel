$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Assert-FileContains([string]$Path, [string]$Pattern) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: $Path"
    }
    if (-not (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet)) {
        throw "Expected pattern '$Pattern' in $Path"
    }
}

$profile = Join-Path $root 'novel-front/src/main/resources/application-monitoring.yml'
Assert-FileContains $profile '^\s*address:\s*0\.0\.0\.0\s*$'
Assert-FileContains $profile '^\s*percentiles-histogram:'
Assert-FileContains $profile '^\s*http\.server\.requests:\s*true\s*$'

$kafkaCheck = Join-Path $root 'performance/check-book-visit-kafka.ps1'
Assert-FileContains $kafkaCheck '\[System\.Net\.Http\.HttpClientHandler\]::new\(\)'
Assert-FileContains $kafkaCheck 'MaxConnectionsPerServer\s*=\s*\$MaxConcurrency'

$compose = Join-Path $root 'compose.local.yml'
Assert-FileContains $compose '^\s{2}prometheus:\s*$'
Assert-FileContains $compose '^\s{2}grafana:\s*$'
Assert-FileContains $compose '127\.0\.0\.1:9090:9090'
Assert-FileContains $compose '127\.0\.0\.1:3000:3000'

$prometheus = Join-Path $root 'monitoring/prometheus/prometheus.yml'
Assert-FileContains $prometheus 'host\.docker\.internal:8084'
Assert-FileContains $prometheus '/actuator/prometheus'

$rules = Join-Path $root 'monitoring/prometheus/rules/novel-plus-alerts.yml'
@(
    'NovelFrontDown'
    'ChapterCacheErrors'
    'LowChapterCacheHitRate'
    'KafkaPublishFailures'
    'ReadingEngagementRedisErrors'
    'ReadingEngagementKafkaPublishFailures'
    'KafkaConsumerLagHigh'
    'HikariPendingConnections'
    'JvmHeapUsageHigh'
    'FrontExecutorRejectedTasks'
    'FrontExecutorQueueSaturated'
) | ForEach-Object {
    Assert-FileContains $rules ("alert:\s*" + $_)
}

@(
    'monitoring/prometheus/prometheus.yml'
    'monitoring/prometheus/rules/novel-plus-alerts.yml'
    'monitoring/grafana/provisioning/datasources/prometheus.yml'
    'monitoring/grafana/provisioning/dashboards/dashboards.yml'
    'monitoring/grafana/dashboards/novel-plus-overview.json'
    'performance/check-reading-engagement.ps1'
) | ForEach-Object {
    $path = Join-Path $root $_
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required file is missing: $path"
    }
}

$dashboardPath = Join-Path $root 'monitoring/grafana/dashboards/novel-plus-overview.json'
$dashboard = Get-Content -LiteralPath $dashboardPath -Raw | ConvertFrom-Json
if ($dashboard.uid -ne 'novel-plus-overview') {
    throw 'Unexpected dashboard UID.'
}
if ($dashboard.title -ne 'Novel-Plus Overview') {
    throw 'Unexpected dashboard title.'
}
if ($dashboard.panels.Count -lt 12) {
    throw 'Dashboard must contain at least 12 panels.'
}
$httpLatencyPanel = $dashboard.panels | Where-Object { $_.title -eq 'HTTP Average / P95 / P99' }
if (-not $httpLatencyPanel) {
    throw 'HTTP average and percentile latency panel is missing.'
}
if ('Average' -notin @($httpLatencyPanel.targets.legendFormat)) {
    throw 'HTTP average latency target is missing.'
}

$executorPanel = $dashboard.panels | Where-Object { $_.title -eq 'Front Executor Utilization' }
if (-not $executorPanel) {
    throw 'Chapter executor utilization panel is missing.'
}
@(
    'executor_active_threads{name="novel.front.executor"}'
    'executor_pool_size_threads{name="novel.front.executor"}'
) | ForEach-Object {
    if ($_ -notin @($executorPanel.targets.expr)) {
        throw "Chapter executor utilization query is missing: $_"
    }
}

$executorQueuePanel = $dashboard.panels | Where-Object { $_.title -eq 'Front Executor Queue / Rejections' }
if (-not $executorQueuePanel) {
    throw 'Chapter executor queue and rejection panel is missing.'
}
@(
    'executor_queued_tasks{name="novel.front.executor"}'
    'executor_queue_remaining_tasks{name="novel.front.executor"}'
    'sum(rate(novel_front_executor_rejections_total[$__rate_interval]))'
) | ForEach-Object {
    if ($_ -notin @($executorQueuePanel.targets.expr)) {
        throw "Chapter executor queue or rejection query is missing: $_"
    }
}

$readingQueries = @{
    'Reading Heartbeat Outcomes' = 'sum by (result) (rate(novel_reading_heartbeat_total[$__rate_interval]))'
    'Reading Credited Seconds / s' = 'sum(rate(novel_reading_credited_seconds_total[$__rate_interval]))'
    'Reading Kafka Send / s' = 'sum by (result) (rate(novel_reading_kafka_send_total[$__rate_interval]))'
}
foreach ($title in $readingQueries.Keys) {
    $panel = @($dashboard.panels | Where-Object { $_.title -eq $title })
    if ($panel.Count -ne 1 -or $readingQueries[$title] -notin @($panel.targets.expr)) {
        throw "Reading engagement panel or exact query is missing: $title"
    }
}
Assert-FileContains $rules ([regex]::Escape('sum(increase(novel_reading_heartbeat_total{result="redis_error"}[5m])) > 0'))
Assert-FileContains $rules ([regex]::Escape('sum(increase(novel_reading_kafka_send_total{result="failed"}[5m])) > 0'))
$ruleText = Get-Content -LiteralPath $rules -Raw
$redisRule = [regex]::Match($ruleText, '(?ms)^      - alert: ReadingEngagementRedisErrors\r?\n.*?(?=^      - alert:|\z)').Value
$kafkaRule = [regex]::Match($ruleText, '(?ms)^      - alert: ReadingEngagementKafkaPublishFailures\r?\n.*?(?=^      - alert:|\z)').Value
if ($redisRule -notmatch '(?m)^        for: 1m\s*$' -or $kafkaRule -match '(?m)^        for:') {
    throw 'Reading Redis alert must wait 1m; reading Kafka alert must evaluate immediately.'
}
foreach ($relativePath in @('performance/start-front-monitoring.ps1', 'performance/check-reading-engagement.ps1', 'performance/check-observability.ps1', 'performance/test-observability-config.ps1')) {
    $parseErrors = $null
    $tokens = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root $relativePath), [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw "PowerShell syntax errors in $relativePath" }
}

$startupScript = Join-Path $root 'performance/start-front-monitoring.ps1'
$startupText = Get-Content -LiteralPath $startupScript -Raw
if ($startupText -match '(?i)C:\\Users\\[^\\]+\\\.m2\\repository') {
    throw 'Monitoring startup must not contain a developer-specific Maven repository path.'
}
Assert-FileContains $startupScript "GetFolderPath\('UserProfile'\)"

$persistenceQueries = @{
    'Reading Persisted Seconds / s' = @('sum(rate(novel_reading_kafka_persisted_seconds_total[$__rate_interval]))')
    'Reading Deduplicated Events / s' = @('sum(rate(novel_reading_kafka_deduplicated_total[$__rate_interval]))')
    'Reading Daily Rows Updated / s' = @('sum(rate(novel_reading_kafka_daily_rows_updated_total[$__rate_interval]))')
    'Reading Kafka Retry / DLT' = @('sum(rate(novel_reading_kafka_retry_total[$__rate_interval]))', 'sum(rate(novel_reading_kafka_dlt_total[$__rate_interval]))')
    'Reading Dedup Cleanup' = @('sum(increase(novel_reading_dedup_cleanup_deleted_total[24h]))', 'sum(increase(novel_reading_dedup_cleanup_failures_total[24h]))')
}
foreach ($title in $persistenceQueries.Keys) {
    $panels = @($dashboard.panels | Where-Object { $_.title -eq $title })
    if ($panels.Count -ne 1) { throw "Persistence panel must exist exactly once: $title" }
    foreach ($query in $persistenceQueries[$title]) {
        if ($query -notin @($panels[0].targets.expr)) { throw "Persistence query missing: $query" }
    }
}
if (@($dashboard.panels.id | Select-Object -Unique).Count -ne $dashboard.panels.Count) {
    throw 'Dashboard panel IDs must be unique.'
}
for ($i = 0; $i -lt $dashboard.panels.Count; $i++) {
    $a = $dashboard.panels[$i].gridPos
    for ($j = $i + 1; $j -lt $dashboard.panels.Count; $j++) {
        $b = $dashboard.panels[$j].gridPos
        if ($a.x -lt ($b.x + $b.w) -and $b.x -lt ($a.x + $a.w) -and
            $a.y -lt ($b.y + $b.h) -and $b.y -lt ($a.y + $a.h)) {
            throw "Dashboard panels overlap: $i and $j"
        }
    }
}
$persistenceAlerts = @{
    ReadingConsumerLagHigh = @('sum(kafka_consumer_fetch_manager_records_lag{topic="novel-reading-engagement-v1"}) > 10000', '2m')
    ReadingConsumerDltGrowth = @('sum(increase(novel_reading_kafka_dlt_total[5m])) > 0', '')
    ReadingConsumerRetries = @('sum(rate(novel_reading_kafka_retry_total[5m])) > 0', '1m')
    ReadingConsumerDltPublishFailures = @('sum(increase(novel_reading_kafka_dlt_publish_failures_total[5m])) > 0', '')
    ReadingDedupCleanupFailures = @('sum(increase(novel_reading_dedup_cleanup_failures_total[24h])) > 0', '')
}
foreach ($name in $persistenceAlerts.Keys) {
    $matches = [regex]::Matches($ruleText, ('(?ms)^      - alert: ' + $name + '\r?\n.*?(?=^      - alert:|\z)'))
    if ($matches.Count -ne 1) { throw "Persistence alert must exist exactly once: $name" }
    $block = $matches[0].Value
    if (-not $block.Contains($persistenceAlerts[$name][0])) { throw "Wrong persistence alert expression: $name" }
    $hold = $persistenceAlerts[$name][1]
    if ($hold -and $block -notmatch ('(?m)^        for: ' + $hold + '\s*$')) { throw "Wrong alert hold: $name" }
    if (-not $hold -and $block -match '(?m)^        for:') { throw "Alert must evaluate immediately: $name" }
}

Write-Host 'Observability configuration contract passed.'
