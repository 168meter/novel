param(
    [string]$RedisPort = '6380',
    [string]$RedisPassword = '123456',
    [string]$MavenCommand = '',
    [string]$ReadingIpHmacSecret = '',
    [switch]$UseMailpit
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$activeProfiles = 'dev,monitoring'

function Resolve-MavenCommand([string]$RequestedCommand) {
    if ($RequestedCommand) {
        if (Test-Path -LiteralPath $RequestedCommand -PathType Leaf) {
            return (Resolve-Path -LiteralPath $RequestedCommand).Path
        }
        $requested = Get-Command $RequestedCommand -ErrorAction SilentlyContinue
        if ($requested) {
            return $requested.Source
        }
        throw "Specified Maven command was not found: $RequestedCommand"
    }

    foreach ($name in @('mvn.cmd', 'mvn')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }

    if ($env:MAVEN_HOME) {
        $mavenHomeCommand = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path -LiteralPath $mavenHomeCommand -PathType Leaf) {
            return $mavenHomeCommand
        }
    }

    $intellijPatterns = @(
        'D:\IntelliJ IDEA *\plugins\maven\lib\maven3\bin\mvn.cmd'
        'C:\Program Files\JetBrains\IntelliJ IDEA *\plugins\maven\lib\maven3\bin\mvn.cmd'
        "$env:LOCALAPPDATA\Programs\IntelliJ IDEA *\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
    foreach ($pattern in $intellijPatterns) {
        $bundled = Get-Item -Path $pattern -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($bundled) {
            return $bundled.FullName
        }
    }

    throw 'Maven was not found. Add mvn to PATH, set MAVEN_HOME, or pass -MavenCommand with the full mvn.cmd path.'
}

$requiredEnvironmentVariables = @(
    'JWT_SECRET'
    'CACHE_MANAGER_PASSWORD'
    'NOVEL_AUTH_HMAC_SECRET'
)
foreach ($variableName in $requiredEnvironmentVariables) {
    $value = [Environment]::GetEnvironmentVariable($variableName, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required environment variable is missing: $variableName"
    }
}

$maven = Resolve-MavenCommand $MavenCommand
$gitCommonDirectory = (& git -C $root rev-parse --path-format=absolute --git-common-dir 2>$null)
if ($LASTEXITCODE -eq 0 -and $gitCommonDirectory) {
    $runtimeRoot = Split-Path -Parent $gitCommonDirectory.Trim()
}
else {
    $runtimeRoot = $root
}
$shardingConfig = Join-Path $runtimeRoot 'config\shardingsphere-jdbc.yml'
if (-not (Test-Path -LiteralPath $shardingConfig -PathType Leaf)) {
    throw "Runtime ShardingSphere configuration was not found: $shardingConfig"
}
$runtimeConfigPreparer = Join-Path $PSScriptRoot 'prepare-shardingsphere-runtime-config.ps1'
if (-not (Test-Path -LiteralPath $runtimeConfigPreparer -PathType Leaf)) {
    throw "Runtime ShardingSphere configuration preparer was not found: $runtimeConfigPreparer"
}

$mailArguments = if ($UseMailpit) {
    '-Dspring.mail.host=127.0.0.1 -Dspring.mail.port=1025 -Dspring.mail.username=acceptance@novel.local ' +
        '-Dspring.mail.properties.mail.smtp.auth=false ' +
        '-Dspring.mail.properties.mail.smtp.ssl.enable=false'
}
else { '' }
$mavenRepository = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2\repository'
Write-Host "Using Maven: $maven"
Write-Host "Activating Spring profiles: $activeProfiles (plus profiles included by application.yml)"
if ($UseMailpit) { Write-Host 'Using local Mailpit SMTP on 127.0.0.1:1025.' }

Push-Location $root
$previousReadingSecret = $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET
$generatedRuntimeDirectory = $null
try {
    $generatedRuntimeDirectory = Join-Path ([IO.Path]::GetTempPath()) `
        ("novel-front-sharding-" + [guid]::NewGuid().ToString('N'))
    $generatedShardingConfig = Join-Path $generatedRuntimeDirectory 'shardingsphere-jdbc.yml'
    & $runtimeConfigPreparer -SourcePath $shardingConfig -DestinationPath $generatedShardingConfig `
        -ConnectionTimeoutMs 5000 -ValidationTimeoutMs 3000
    $shardingJdbcPath = $generatedShardingConfig.Replace('\', '/')
    $dataSourceUrl = "jdbc:shardingsphere:absolutepath:$shardingJdbcPath"
    $jvmArguments = "-XX:TieredStopAtLevel=1 -Duser.dir=`"$runtimeRoot`" -Dspring.datasource.url=`"$dataSourceUrl`" -Dspring.profiles.active=$activeProfiles -Dspring.data.redis.port=$RedisPort -Dspring.data.redis.password=$RedisPassword $mailArguments"
    Write-Host 'Using a generated runtime database configuration with bounded connection waits.'

    if ($ReadingIpHmacSecret) {
        $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET = $ReadingIpHmacSecret
    }
    elseif (-not $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET) {
        $secretBytes = New-Object byte[] 32
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($secretBytes) } finally { $random.Dispose() }
        $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET = [Convert]::ToBase64String($secretBytes)
        [Array]::Clear($secretBytes, 0, $secretBytes.Length)
        Write-Host 'Generated a process-local reading IP HMAC secret.'
    }
    & $maven `
        '-pl' 'novel-common' `
        '-am' `
        '-DskipTests' `
        "-Dmaven.repo.local=$mavenRepository" `
        '-Dmaven.compiler.fork=true' `
        'install'

    if ($LASTEXITCODE -ne 0) {
        throw "novel-common reactor dependency install exited with code $LASTEXITCODE."
    }

    & $maven `
        '-pl' 'novel-front' `
        '-DskipTests' `
        "-Dmaven.repo.local=$mavenRepository" `
        '-Dmaven.compiler.fork=true' `
        "-Dspring-boot.run.jvmArguments=$jvmArguments" `
        'spring-boot:run'

    if ($LASTEXITCODE -ne 0) {
        throw "novel-front exited with code $LASTEXITCODE."
    }
}
finally {
    $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET = $previousReadingSecret
    if ($generatedRuntimeDirectory -and (Test-Path -LiteralPath $generatedRuntimeDirectory)) {
        Remove-Item -LiteralPath $generatedRuntimeDirectory -Recurse -Force
    }
    Pop-Location
}
