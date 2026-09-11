param(
    [string]$RedisPort = '6380',
    [string]$RedisPassword = '123456',
    [string]$MavenCommand = '',
    [string]$ReadingIpHmacSecret = ''
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

$jvmArguments = "-XX:TieredStopAtLevel=1 -Duser.dir=`"$runtimeRoot`" -Dspring.profiles.active=$activeProfiles -Dspring.data.redis.port=$RedisPort -Dspring.data.redis.password=$RedisPassword"
Write-Host "Using Maven: $maven"
Write-Host "Using runtime configuration: $shardingConfig"
Write-Host "Activating Spring profiles: $activeProfiles (plus profiles included by application.yml)"

Push-Location $root
$previousReadingSecret = $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET
try {
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
        '-pl' 'novel-front' `
        '-DskipTests' `
        '-Dmaven.repo.local=C:\Users\26635\.m2\repository' `
        '-Dmaven.compiler.fork=true' `
        "-Dspring-boot.run.jvmArguments=$jvmArguments" `
        'spring-boot:run'

    if ($LASTEXITCODE -ne 0) {
        throw "novel-front exited with code $LASTEXITCODE."
    }
}
finally {
    $env:NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET = $previousReadingSecret
    Pop-Location
}
