$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$helper = Join-Path $root 'deploy/scripts/start-local-front.ps1'
$fixtureDirectory = Join-Path $root ('deploy/runtime/local/verification-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $fixtureDirectory)
$variables = @('MYSQL_HOST_PORT', 'REDIS_HOST_PORT', 'KAFKA_HOST_PORT',
    'MAILPIT_SMTP_HOST_PORT', 'MAILPIT_UI_HOST_PORT', 'PROMETHEUS_HOST_PORT', 'GRAFANA_HOST_PORT')
$previous = @{}
foreach ($name in $variables) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue
}

try {
    $cases = @(
        @{ Name = 'defaults'; Ports = @(3307, 6380, 9092, 1025, 8025, 9090, 3000); Overrides = $false },
        @{ Name = 'overrides'; Ports = @(13307, 16380, 19092, 11025, 18025, 19090, 13000); Overrides = $true }
    )
    foreach ($case in $cases) {
        $fixture = Join-Path $fixtureDirectory ($case.Name + '.env')
        $lines = @('MYSQL_DATABASE=novel_plus', 'MYSQL_ROOT_PASSWORD=fixture-only',
            'REDIS_PASSWORD=fixture-only', 'GRAFANA_ADMIN_PASSWORD=fixture-only',
            'JWT_SECRET=fixture-only', 'CACHE_MANAGER_PASSWORD=fixture-only',
            'NOVEL_AUTH_HMAC_SECRET=fixture-only', 'NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET=fixture-only')
        if ($case.Overrides) {
            for ($index = 0; $index -lt $variables.Count; $index++) {
                $lines += "$($variables[$index])=$($case.Ports[$index])"
            }
        }
        [IO.File]::WriteAllLines($fixture, $lines, [Text.UTF8Encoding]::new($false))
        $json = & docker compose --env-file $fixture -f (Join-Path $root 'compose.local.yml') config --format json
        if ($LASTEXITCODE -ne 0) { throw "Compose failed for $($case.Name)." }
        $rendered = ($json -join "`n") | ConvertFrom-Json
        $mappings = @(
            @('mysql', 3306), @('redis', 6379), @('kafka', 9092),
            @('mailpit', 1025), @('mailpit', 8025), @('prometheus', 9090), @('grafana', 3000)
        )
        for ($index = 0; $index -lt $mappings.Count; $index++) {
            $service = $rendered.services.($mappings[$index][0])
            $mapping = @($service.ports | Where-Object target -EQ $mappings[$index][1])
            if ($mapping.Count -ne 1 -or [int]$mapping[0].published -ne $case.Ports[$index]) {
                throw "$($case.Name): wrong published port for $($mappings[$index][0])."
            }
            if ($index -ge 3 -and $mapping[0].host_ip -ne '127.0.0.1') {
                throw 'Mailpit and monitoring ports must remain loopback-only.'
            }
        }
        $advertised = $rendered.services.kafka.environment.KAFKA_ADVERTISED_LISTENERS
        if ($advertised -ne "PLAINTEXT_HOST://127.0.0.1:$($case.Ports[2]),PLAINTEXT://novel-kafka:19092") {
            throw "$($case.Name): Kafka advertises the wrong host port."
        }
        $settings = & $helper -EnvFile $fixture -ValidateOnly
        for ($index = 0; $index -lt $variables.Count; $index++) {
            if ($settings.Ports.($variables[$index]) -ne $case.Ports[$index]) {
                throw "$($case.Name): application helper disagrees with Compose."
            }
        }
        if ($settings.Profiles -ne 'dev,monitoring' -or
            $settings.ConfigLocation -notmatch '/deploy/local/application.yml$') {
            throw 'Helper must configure Spring through the environment.'
        }
        if (-not $case.Overrides) {
            $env:MYSQL_HOST_PORT = '23307'
            $environmentSettings = & $helper -EnvFile $fixture -ValidateOnly
            if ($environmentSettings.Ports.MYSQL_HOST_PORT -ne 23307) {
                throw 'Explicit process environment must override the env file.'
            }
            Remove-Item Env:MYSQL_HOST_PORT
        }
        Write-Host "PASS: $($case.Name), Compose/application ports and Kafka advertisement agree."
    }
    $invalidFixture = Join-Path $fixtureDirectory 'invalid.env'
    $invalidLines = @('MYSQL_HOST_PORT=70000', 'MYSQL_ROOT_PASSWORD=fixture-only',
        'REDIS_PASSWORD=fixture-only', 'JWT_SECRET=fixture-only', 'CACHE_MANAGER_PASSWORD=fixture-only',
        'NOVEL_AUTH_HMAC_SECRET=fixture-only', 'NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET=fixture-only')
    [IO.File]::WriteAllLines($invalidFixture, $invalidLines, [Text.UTF8Encoding]::new($false))
    $rejected = $false
    try { & $helper -EnvFile $invalidFixture -ValidateOnly | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Helper accepted an invalid port.' }
    $missingFixture = Join-Path $fixtureDirectory 'missing.env'
    [IO.File]::WriteAllLines($missingFixture, @('MYSQL_HOST_PORT=3307'), [Text.UTF8Encoding]::new($false))
    $rejected = $false
    try { & $helper -EnvFile $missingFixture -ValidateOnly | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Helper accepted missing secrets.' }
    Write-Host 'PASS: invalid ports and missing secrets are rejected before startup.'
}
finally {
    foreach ($name in $variables) {
        if ($null -eq $previous[$name]) {
            Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue
        }
        else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    }
}
