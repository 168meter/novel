[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dashboard = Get-Content (Join-Path $root 'monitoring/grafana/dashboards/novel-plus-overview.json') -Raw | ConvertFrom-Json
$panels = @($dashboard.panels | Where-Object { $_.type -ne 'row' })
if ($dashboard.uid -ne 'novel-plus-overview' -or $panels.Count -ne 12) {
    throw 'Benchmark Overview must retain its UID and contain exactly 12 core panels.'
}
if (@($panels.id | Select-Object -Unique).Count -ne 12) { throw 'Duplicate panel IDs.' }
$variables = @($dashboard.templating.list)
foreach ($name in @('job', 'instance')) {
    if (@($variables | Where-Object { $_.name -eq $name }).Count -ne 1) { throw "Missing scope variable: $name" }
}
foreach ($panel in $panels) {
    if (-not $panel.description -or $panel.fieldConfig.defaults.noValue -ne 'N/A') { throw "Missing semantics or N/A: $($panel.title)" }
    foreach ($target in $panel.targets) {
        $expr = $target.expr
        if (-not $expr.Contains('job=~"$job"') -or -not $expr.Contains('instance=~"$instance"')) {
            throw "Unscoped query: $($panel.title) / $($target.refId)"
        }
        if ($expr -match '\bor\s+vector\s*\(|clamp_min|executor_(?:idle_)?seconds|novel_auth_') {
            throw "Fabricated zero, unrecorded executor timer or non-core auth query: $expr"
        }
        if ($expr -match '(?i)(?:bookId|chapterId|userId|sessionId|ip)\s*=') { throw "High cardinality label: $expr" }
        if ($expr -match 'http_server_requests') {
            if (-not $expr.Contains('uri="/book/{bookId}/{bookIndexId}.html"') -or -not $expr.Contains('method="GET"')) {
                throw 'Chapter HTTP query includes unrelated traffic.'
            }
        }
        if ($expr -match 'histogram_quantile') {
            if (-not $expr.Contains('status="200"') -or -not $expr.Contains('by (instance, le)')) {
                throw 'Successful request histogram must retain instance and le.'
            }
        }
    }
}
$latency = $panels | Where-Object { $_.id -eq 3 }
foreach ($id in @(3, 4, 5, 10)) {
    if ('lastNotNull' -in @(($panels | Where-Object { $_.id -eq $id }).options.legend.calcs)) {
        throw 'Historical latency or ratio samples must not appear as a current last-non-null summary.'
    }
}
if (@($latency.targets).Count -ne 3 -or @($latency.targets.legendFormat | Where-Object { $_ -match 'P(?:50|95|99)' }).Count -ne 3) {
    throw 'Chapter latency must have P50/P95/P99.'
}
foreach ($id in @(4, 5)) {
    if (-not (($panels | Where-Object { $_.id -eq $id }).targets.expr -join ' ').Contains('> 0')) { throw 'Ratios need an active denominator.' }
}
if (($panels | Where-Object { $_.id -eq 6 }).title -notmatch 'Content Loader') { throw 'Loader must not be labelled all DB reads.' }
if (($panels | Where-Object { $_.id -eq 9 }).title -notmatch 'Client Fetch Lag') { throw 'Kafka lag semantics missing.' }
foreach ($id in @(1, 5, 7, 8, 10, 11, 12)) {
    $panel = $panels | Where-Object { $_.id -eq $id }
    if (@($panel.fieldConfig.overrides).Count -eq 0) { throw "Mixed-unit panel lacks overrides: $id" }
}
foreach ($id in @(7, 10, 11)) {
    $panel = $panels | Where-Object { $_.id -eq $id }
    $percentageAxes = @($panel.fieldConfig.overrides | Where-Object {
        @($_.properties | Where-Object { $_.id -eq 'custom.axisSoftMax' -and $_.value -eq 1 }).Count -gt 0
    })
    if ($percentageAxes.Count -eq 0) { throw "Percentage axes need a fractional soft maximum: $id" }
}
for ($i=0; $i -lt $panels.Count; $i++) {
    $a=$panels[$i].gridPos
    for ($j=$i+1; $j -lt $panels.Count; $j++) {
        $b=$panels[$j].gridPos
        if ($a.x -lt $b.x+$b.w -and $b.x -lt $a.x+$a.w -and $a.y -lt $b.y+$b.h -and $b.y -lt $a.y+$a.h) { throw 'Panel overlap.' }
    }
}
# Test the effective Compose model, not YAML source formatting. With no real env file,
# supply only ephemeral test placeholders and restore this process environment afterwards.
$names=@('MYSQL_ROOT_PASSWORD','REDIS_PASSWORD','GRAFANA_ADMIN_PASSWORD')
$saved=@{}
try {
    foreach ($name in $names) {
        $saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
        [Environment]::SetEnvironmentVariable($name,'EXAMPLE_ONLY_observability_test','Process')
    }
    $model = & docker compose --env-file .env.example -f compose.local.yml config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed.' }
    $compose=$model -join "`n" | ConvertFrom-Json
    if ('host.docker.internal=host-gateway' -notin @($compose.services.prometheus.extra_hosts)) {
        throw 'Prometheus host-gateway mapping missing from effective Compose configuration.'
    }
}
finally {
    foreach ($name in $saved.Keys) {
        if ($null -eq $saved[$name]) {
            Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue
        } else { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    }
}
foreach ($relative in @('monitoring/grafana/provisioning/datasources/prometheus.yml',
    'monitoring/grafana/provisioning/dashboards/dashboards.yml','monitoring/prometheus/prometheus.yml')) {
    if (-not (Test-Path (Join-Path $root $relative))) { throw "Missing provisioning: $relative" }
}
Write-Host 'Benchmark observability configuration contract passed (12 panels, scope, units, no-data, Compose).'
