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
    'KafkaConsumerLagHigh'
    'HikariPendingConnections'
    'JvmHeapUsageHigh'
) | ForEach-Object {
    Assert-FileContains $rules ("alert:\s*" + $_)
}

@(
    'monitoring/prometheus/prometheus.yml'
    'monitoring/prometheus/rules/novel-plus-alerts.yml'
    'monitoring/grafana/provisioning/datasources/prometheus.yml'
    'monitoring/grafana/provisioning/dashboards/dashboards.yml'
    'monitoring/grafana/dashboards/novel-plus-overview.json'
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

Write-Host 'Observability configuration contract passed.'
