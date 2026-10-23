[CmdletBinding()]
param(
    [string]$EnvFile = '.env',
    [string]$MavenCommand = '',
    [switch]$SkipBuild,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$previousEnvironment = @{}
function Set-LocalEnvironment([string]$Name, [string]$Value) {
    if (-not $previousEnvironment.ContainsKey($Name)) {
        $previousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

try {
    if (-not [IO.Path]::IsPathRooted($EnvFile)) { $EnvFile = Join-Path $projectRoot $EnvFile }
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
        throw 'Local env file is missing. Copy .env.example to .env and set local secrets.'
    }
    foreach ($line in [IO.File]::ReadAllLines($EnvFile)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            throw 'Env file must contain literal KEY=VALUE assignments, one per line.'
        }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith("'") -and $value.EndsWith("'")) -or
            ($value.StartsWith('"') -and $value.EndsWith('"')))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($value.Contains('${') -or $value.Contains("`n") -or $value.Contains("`r")) {
            throw "Use a literal single-line value for $name; env-file interpolation is unsupported."
        }
        # Same precedence as Compose: explicit process environment wins over .env.
        if ($null -eq [Environment]::GetEnvironmentVariable($name, 'Process')) {
            Set-LocalEnvironment $name $value
        }
    }
    $ports = [ordered]@{
        MYSQL_HOST_PORT = 3307; REDIS_HOST_PORT = 6380; KAFKA_HOST_PORT = 9092
        MAILPIT_SMTP_HOST_PORT = 1025; MAILPIT_UI_HOST_PORT = 8025
        PROMETHEUS_HOST_PORT = 9090; GRAFANA_HOST_PORT = 3000
    }
    foreach ($name in @($ports.Keys)) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrEmpty($value)) { $value = [string]$ports[$name] }
        $port = 0
        if ($value -notmatch '^\d+$' -or -not [int]::TryParse($value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
            throw "Invalid TCP port in $name."
        }
        $ports[$name] = $port
        Set-LocalEnvironment $name ([string]$port)
    }
    if (-not $env:MYSQL_DATABASE) { Set-LocalEnvironment 'MYSQL_DATABASE' 'novel_plus' }
    if ($env:MYSQL_DATABASE -notmatch '^[A-Za-z0-9_]+$') { throw 'MYSQL_DATABASE must be an SQL identifier.' }
    foreach ($name in @('MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD', 'JWT_SECRET',
        'CACHE_MANAGER_PASSWORD', 'NOVEL_AUTH_HMAC_SECRET', 'NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('EXAMPLE_ONLY_')) {
            throw "Required local secret is MISSING or still an example placeholder: $name"
        }
    }
    $configPath = (Join-Path $projectRoot 'deploy/local/application.yml').Replace('\', '/')
    $shardingPath = (Join-Path $projectRoot 'deploy/local/shardingsphere-jdbc.yml').Replace('\', '/')
    foreach ($path in @($configPath, $shardingPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Tracked local configuration is missing.' }
    }
    # main() currently discards command-line args. Environment is read by Spring directly.
    Set-LocalEnvironment 'SPRING_PROFILES_ACTIVE' 'dev,monitoring'
    Set-LocalEnvironment 'SPRING_CONFIG_ADDITIONAL_LOCATION' ('file:' + $configPath)
    Set-LocalEnvironment 'NOVEL_LOCAL_SHARDING_FILE' $shardingPath
    Set-LocalEnvironment 'NOVEL_LOCAL_RUNTIME_DIR' ((Join-Path $projectRoot 'deploy/runtime/local').Replace('\', '/'))
    $passwordScalar = "'" + $env:MYSQL_ROOT_PASSWORD.Replace("'", "''") + "'"
    # 5.5.1 uses Matcher.appendReplacement: quote $ and backslash in its replacement.
    Set-LocalEnvironment 'NOVEL_LOCAL_MYSQL_PASSWORD_YAML' ($passwordScalar.Replace('\', '\\').Replace('$', '\$'))
    if ($ValidateOnly) {
        [pscustomobject]@{ ProjectRoot = $projectRoot; Profiles = $env:SPRING_PROFILES_ACTIVE
            ConfigLocation = $env:SPRING_CONFIG_ADDITIONAL_LOCATION; ShardingFile = $shardingPath; Ports = $ports }
        return
    }
    $maven = if ($MavenCommand) { Get-Command $MavenCommand -ErrorAction Stop }
        else { Get-Command mvn.cmd, mvn -ErrorAction SilentlyContinue | Select-Object -First 1 }
    if (-not $maven) { throw 'Maven is missing. Add mvn to PATH or pass -MavenCommand.' }
    if (-not $env:JAVA_HOME) {
        $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
        if ($javaHome) { Set-LocalEnvironment 'JAVA_HOME' $javaHome }
    }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
    $javaVersion = & $java --version
    if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch '(?:openjdk|java) 21[. ]') {
        throw 'Java 21 is required.'
    }
    if (Get-NetTCPConnection -State Listen -LocalPort 8083,8084 -ErrorAction SilentlyContinue) {
        throw '8083 or 8084 is occupied. This helper does not stop existing processes.'
    }
    Write-Host 'Starting local novel-front with dev,monitoring; secrets remain in process environment.'
    Push-Location $projectRoot
    try {
        if (-not $SkipBuild) {
            & $maven.Source '-pl' 'novel-front' '-am' '-DskipTests' '-Dmaven.antrun.skip=true' install
            if ($LASTEXITCODE -ne 0) { throw 'Local reactor dependency installation failed.' }
        }
        & $maven.Source '-pl' 'novel-front' spring-boot:run
        if ($LASTEXITCODE -ne 0) { throw 'novel-front exited with a failure.' }
    }
    finally { Pop-Location }
}
finally {
    foreach ($name in $previousEnvironment.Keys) {
        if ($null -eq $previousEnvironment[$name]) {
            Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue
        }
        else { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    }
}
