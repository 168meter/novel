$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$benchmark = Join-Path $PSScriptRoot 'benchmark-argon2.ps1'
$acceptance = Join-Path $PSScriptRoot 'check-authentication-security.ps1'
$runner = Join-Path $root 'novel-front/src/test/java/com/java2nb/novel/auth/password/Argon2Benchmark.java'
$compose = Join-Path $root 'compose.local.yml'
$launcher = Join-Path $PSScriptRoot 'start-front-monitoring.ps1'
$runtimeConfigPreparer = Join-Path $PSScriptRoot 'prepare-shardingsphere-runtime-config.ps1'
$packagedShardingConfig = Join-Path $root 'novel-front/src/main/build/config/shardingsphere-jdbc.yml'

foreach ($path in @($benchmark, $acceptance, $runner, $runtimeConfigPreparer, $packagedShardingConfig)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Authentication security verification artifact is missing: $path"
    }
}

$packagedShardingText = Get-Content -LiteralPath $packagedShardingConfig -Raw
foreach ($required in @('connectionTimeout: 5000', 'validationTimeout: 3000')) {
    if ($packagedShardingText -notmatch [regex]::Escape($required)) {
        throw "Packaged ShardingSphere fail-fast contract is missing: $required"
    }
}

$runtimeConfigTempRoot = Join-Path ([IO.Path]::GetTempPath()) ("novel-sharding-config-test-" + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $runtimeConfigTempRoot)
try {
    $runtimeConfigSource = Join-Path $runtimeConfigTempRoot 'source.yml'
    $runtimeConfigOutput = Join-Path $runtimeConfigTempRoot 'generated.yml'
    $runtimeConfigFixture = @'
dataSources:
  ds_1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    jdbcUrl: jdbc:mysql://127.0.0.1:3307/novel_plus
    username: root
    password: secret-one
  ds_2:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    connectionTimeout: 30000
    validationTimeout: 5000
    jdbcUrl: jdbc:mysql://127.0.0.1:3307/information_schema
    username: root
    password: secret-two
rules:
  - !SINGLE
    tables:
      - "*.*"
'@
    Set-Content -LiteralPath $runtimeConfigSource -Value $runtimeConfigFixture -Encoding UTF8
    $sourceBefore = Get-Content -LiteralPath $runtimeConfigSource -Raw
    & $runtimeConfigPreparer -SourcePath $runtimeConfigSource -DestinationPath $runtimeConfigOutput `
        -ConnectionTimeoutMs 5000 -ValidationTimeoutMs 3000
    $generatedConfig = Get-Content -LiteralPath $runtimeConfigOutput -Raw
    $sourceAfter = Get-Content -LiteralPath $runtimeConfigSource -Raw

    if ($sourceAfter -ne $sourceBefore) {
        throw 'Runtime ShardingSphere preparation modified the source configuration.'
    }
    if (($generatedConfig | Select-String -AllMatches '(?m)^    connectionTimeout: 5000\r?$').Matches.Count -ne 2 -or
        ($generatedConfig | Select-String -AllMatches '(?m)^    validationTimeout: 3000\r?$').Matches.Count -ne 2) {
        throw 'Runtime ShardingSphere preparation did not harden every Hikari datasource exactly once.'
    }
    if ($generatedConfig -notmatch [regex]::Escape('password: secret-one') -or
        $generatedConfig -notmatch [regex]::Escape('password: secret-two') -or
        $generatedConfig -notmatch [regex]::Escape('!SINGLE')) {
        throw 'Runtime ShardingSphere preparation did not preserve datasource credentials or YAML tags.'
    }
    if ($env:OS -eq 'Windows_NT') {
        $runtimeConfigAcl = Get-Acl -LiteralPath $runtimeConfigTempRoot
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $unexpectedAllowRules = @($runtimeConfigAcl.Access | Where-Object {
            $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -ne $currentSid
        })
        if (-not $runtimeConfigAcl.AreAccessRulesProtected -or $unexpectedAllowRules.Count -gt 0) {
            throw 'Generated ShardingSphere credential directory is readable by identities other than the current user.'
        }
    }
}
finally {
    Remove-Item -LiteralPath $runtimeConfigTempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$composeText = Get-Content -LiteralPath $compose -Raw
foreach ($required in @(
    'mailpit:'
    'axllent/mailpit:v1.31.1'
    '127.0.0.1:1025:1025'
    '127.0.0.1:8025:8025'
    '"/mailpit", "readyz"'
)) {
    if ($composeText -notmatch [regex]::Escape($required)) {
        throw "Local Mailpit contract is missing: $required"
    }
}
if ($composeText -match '(?m)^\s*-\s*"?(?:0\.0\.0\.0:)?(?:1025|8025):') {
    throw 'Mailpit ports must bind explicitly to 127.0.0.1.'
}

$launcherText = Get-Content -LiteralPath $launcher -Raw
foreach ($required in @(
    '[switch]$UseMailpit'
    '-Dspring.mail.host=127.0.0.1'
    '-Dspring.mail.port=1025'
    '-Dspring.mail.properties.mail.smtp.auth=false'
    '-Dspring.mail.properties.mail.smtp.ssl.enable=false'
    '-Dspring.mail.username=acceptance@novel.local'
    'prepare-shardingsphere-runtime-config.ps1'
    '-Dspring.datasource.url='
    'Remove-Item -LiteralPath $generatedRuntimeDirectory -Recurse -Force'
)) {
    if ($launcherText -notmatch [regex]::Escape($required)) {
        throw "Mailpit launcher contract is missing: $required"
    }
}
$reactorInstallIndex = $launcherText.IndexOf("'install'")
$frontRunIndex = $launcherText.IndexOf("'spring-boot:run'")
$commonInstallScopeIndex = $launcherText.IndexOf("'-pl' 'novel-common'")
if ($launcherText -notmatch [regex]::Escape("'-am'") -or
    $commonInstallScopeIndex -lt 0 -or $commonInstallScopeIndex -gt $reactorInstallIndex -or
    $reactorInstallIndex -lt 0 -or $frontRunIndex -lt 0 -or
    $reactorInstallIndex -gt $frontRunIndex) {
    throw 'Monitoring launcher must install only the latest novel-common reactor dependency before running novel-front.'
}

$acceptanceOutput = & $acceptance -ValidateOnly | Out-String
if ($acceptanceOutput -notmatch 'validated' -or
    $acceptanceOutput -match '(?i)(verification code|jwt|argon2id\$|password=|token=)') {
    throw 'Authentication acceptance validation output is unsafe or incomplete.'
}
$behaviorOutput = & $acceptance -RunBehaviorSelfTest | Out-String
if ($behaviorOutput -notmatch 'cleanup and recovery behavior passed' -or
    $behaviorOutput -match '(?i)(verification code|jwt|argon2id\$|password=|token=)') {
    throw 'Authentication acceptance cleanup/recovery behavior self-test is unsafe or incomplete.'
}
foreach ($unsafeUrl in @('http://example.com', 'https://127.0.0.1:8083', 'not-a-url')) {
    $failed = $false
    try { & $acceptance -ValidateOnly -BaseUrl $unsafeUrl | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw "Authentication acceptance accepted unsafe BaseUrl: $unsafeUrl" }
}
$failed = $false
try { & $acceptance -ValidateOnly -FailureDrill Redis | Out-Null } catch { $failed = $true }
if (-not $failed) { throw 'Authentication acceptance allowed a container stop without explicit permission.' }

$acceptanceText = Get-Content -LiteralPath $acceptance -Raw
if (@([IO.File]::ReadAllBytes($acceptance) | Where-Object { $_ -gt 127 }).Count -gt 0) {
    throw 'Authentication acceptance must remain ASCII-only for Windows PowerShell 5.1 compatibility.'
}
foreach ($required in @(
    'finally {'
    'NOVEL_AUTH_HMAC_SECRET'
    'function Wait-ApplicationHealth'
    '[int]$RequestTimeoutSec = 15'
    "CONCAT_WS('|',password_algorithm"
    'api/v1/search'
    'function Find-MailCode'
    '$message.Snippet'
    'DELETE FROM user WHERE'
    "[ValidateSet('None', 'Redis', 'MySql', 'Kafka', 'Smtp')]"
)) {
    if ($acceptanceText -notmatch [regex]::Escape($required)) {
        throw "Authentication acceptance contract is missing: $required"
    }
}
if ($acceptanceText -match [regex]::Escape("CONCAT(password_algorithm,'\t'")) {
    throw 'Authentication acceptance must not compare MySQL batch-escaped tab characters.'
}
if ($acceptanceText -match [regex]::Escape('Invoke-RestMethod -Uri "$ManagementUrl/actuator/health" -TimeoutSec 5')) {
    throw 'Authentication acceptance must not use a one-shot five-second health check.'
}
if ($acceptanceText -match [regex]::Escape('view/latest.txt?query=')) {
    throw 'Authentication acceptance must not depend on the timing-sensitive Mailpit latest-text view.'
}
foreach ($forbidden in @('KEYS *', 'FLUSHALL', 'FLUSHDB', 'SCAN 0')) {
    if ($acceptanceText -match [regex]::Escape($forbidden)) {
        throw "Authentication acceptance uses broad Redis cleanup: $forbidden"
    }
}
if ($acceptanceText -notmatch "'X-Real-IP'" -or $acceptanceText -match "'X-Forwarded-For'") {
    throw 'Authentication acceptance must drive the configured X-Real-IP trusted-proxy path.'
}
foreach ($required in @(
    'SELECT COUNT(*) FROM user WHERE email='
    '$createdUsers.Add('
    'id=$($user.Id)'
)) {
    if ($acceptanceText -notmatch [regex]::Escape($required)) {
        throw "Authentication cleanup ownership contract is missing: $required"
    }
}
if ($acceptanceText -match [regex]::Escape('DELETE FROM user WHERE email=')) {
    throw 'Authentication cleanup must not delete a user by email alone.'
}
foreach ($required in @(
    'function Assert-CaptchaKeyAgreement'
    "if ((Invoke-Redis EXISTS `$key) -ne '1')"
    '$hmacAgreementProven = Assert-CaptchaKeyAgreement'
    'if (-not $hmacAgreementProven)'
)) {
    if ($acceptanceText -notmatch [regex]::Escape($required)) {
        throw "SMTP failure drill HMAC preflight contract is missing: $required"
    }
}
$agreementIndex = $acceptanceText.IndexOf('$hmacAgreementProven = Assert-CaptchaKeyAgreement')
$stopIndex = $acceptanceText.IndexOf('Stop-LocalContainer $container')
if ($agreementIndex -lt 0 -or $stopIndex -lt 0 -or $agreementIndex -gt $stopIndex) {
    throw 'SMTP HMAC agreement must be proven before Mailpit is stopped.'
}

foreach ($path in @($benchmark, $acceptance, $runtimeConfigPreparer, $launcher, $MyInvocation.MyCommand.Path)) {
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw "PowerShell syntax errors in $path" }
}

$validationOutput = & $benchmark -ValidateOnly -MemoryKiB 19456 -Iterations 2 `
    -Parallelism 1 -Samples 20 -TargetConcurrency 4 | Out-String
if ($validationOutput -notmatch 'validated' -or
    $validationOutput -match '(?i)(password|\$argon2|hash=|encoded)') {
    throw 'Argon2 validation output is missing or contains sensitive material.'
}

foreach ($invalid in @(
    @{ MemoryKiB = 1024; Iterations = 2; Parallelism = 1; Samples = 20; TargetConcurrency = 4 }
    @{ MemoryKiB = 19456; Iterations = 1; Parallelism = 1; Samples = 20; TargetConcurrency = 4 }
    @{ MemoryKiB = 19456; Iterations = 2; Parallelism = 3; Samples = 20; TargetConcurrency = 4 }
    @{ MemoryKiB = 19456; Iterations = 2; Parallelism = 1; Samples = 21; TargetConcurrency = 4 }
    @{ MemoryKiB = 65536; Iterations = 4; Parallelism = 4; Samples = 100; TargetConcurrency = 16 }
)) {
    $failed = $false
    try { & $benchmark -ValidateOnly @invalid | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw "Argon2 benchmark accepted a non-allowlisted or unsafe parameter set: $($invalid | ConvertTo-Json -Compress)" }
}

$runnerText = Get-Content -LiteralPath $runner -Raw
if ($runnerText -match '(?i)System\.out\.(print|printf).*?(password|hash|encoded)') {
    throw 'Argon2 benchmark runner must not print passwords or hashes.'
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("novel-auth-benchmark-" + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $tempRoot)
$fakeMaven = Join-Path $tempRoot 'fake-maven.ps1'
$capturedArguments = Join-Path $tempRoot 'arguments.txt'
$fakeMavenBody = @'
Set-Content -LiteralPath $env:NOVEL_AUTH_BENCHMARK_ARGUMENTS -Value ($args -join "`n")
if ($env:NOVEL_AUTH_BENCHMARK_MODE -eq 'Missing') {
    Write-Output 'ARGON2_BENCHMARK_RESULT operation=encode concurrency=1 samples=20 p50_ms=1 p95_ms=2 throughput_ops_per_sec=3 failures=0'
    return
}
if ($env:NOVEL_AUTH_BENCHMARK_MODE -eq 'Invalid') {
    Write-Output 'ARGON2_BENCHMARK_RESULT operation=encode concurrency=1 samples=20 p50_ms=9 p95_ms=2 throughput_ops_per_sec=0 failures=1'
    Write-Output 'ARGON2_BENCHMARK_RESULT operation=verify concurrency=1 samples=20 p50_ms=1 p95_ms=2 throughput_ops_per_sec=3 failures=0'
    Write-Output 'ARGON2_BENCHMARK_RESULT operation=encode concurrency=4 samples=20 p50_ms=1 p95_ms=2 throughput_ops_per_sec=3 failures=0'
    Write-Output 'ARGON2_BENCHMARK_RESULT operation=verify concurrency=4 samples=20 p50_ms=1 p95_ms=2 throughput_ops_per_sec=3 failures=0'
    return
}
Write-Output 'ARGON2_BENCHMARK_RESULT operation=encode concurrency=1 samples=20 p50_ms=1 p95_ms=2 throughput_ops_per_sec=3 failures=0'
Write-Output 'ARGON2_BENCHMARK_RESULT operation=verify concurrency=1 samples=20 p50_ms=1 p95_ms=2 throughput_ops_per_sec=3 failures=0'
Write-Output 'ARGON2_BENCHMARK_RESULT operation=encode concurrency=4 samples=20 p50_ms=2 p95_ms=3 throughput_ops_per_sec=5 failures=0'
Write-Output 'ARGON2_BENCHMARK_RESULT operation=verify concurrency=4 samples=20 p50_ms=2 p95_ms=3 throughput_ops_per_sec=5 failures=0'
'@
Set-Content -LiteralPath $fakeMaven -Value $fakeMavenBody
$previousArguments = $env:NOVEL_AUTH_BENCHMARK_ARGUMENTS
$previousMode = $env:NOVEL_AUTH_BENCHMARK_MODE
try {
    $env:NOVEL_AUTH_BENCHMARK_ARGUMENTS = $capturedArguments
    $env:NOVEL_AUTH_BENCHMARK_MODE = 'Success'
    $benchmarkOutput = & $benchmark -MemoryKiB 19456 -Iterations 2 -Parallelism 1 `
        -Samples 20 -TargetConcurrency 4 -MavenCommand $fakeMaven -Offline | Out-String
    if (@($benchmarkOutput -split '\r?\n' | Where-Object { $_ -match '^ARGON2_BENCHMARK_RESULT ' }).Count -ne 4) {
        throw 'Argon2 wrapper must emit exactly four validated benchmark result lines.'
    }
    if ($benchmarkOutput -match '(?i)(password|\$argon2|hash=|encoded|sentinel-secret)') {
        throw 'Argon2 wrapper leaked sensitive benchmark material.'
    }
    $arguments = @(Get-Content -LiteralPath $capturedArguments)
    foreach ($required in @(
        '-Dmaven.test.skip=false'
        '-o'
        '-Dtest=com.java2nb.novel.auth.password.Argon2Benchmark'
        '-Dnovel.argon2.benchmark.enabled=true'
        '-Dnovel.argon2.benchmark.memory-kib=19456'
        '-Dnovel.argon2.benchmark.iterations=2'
        '-Dnovel.argon2.benchmark.parallelism=1'
        '-Dnovel.argon2.benchmark.samples=20'
        '-Dnovel.argon2.benchmark.target-concurrency=4'
    )) {
        if ($required -notin $arguments) { throw "Argon2 Maven invocation is missing: $required" }
    }
    $expectedRepository = '-Dmaven.repo.local=' + (Join-Path $env:USERPROFILE '.m2\repository')
    if ($expectedRepository -notin $arguments) {
        throw 'Argon2 Maven invocation did not use the current USERPROFILE repository.'
    }
    if (@($arguments | Where-Object { $_ -like '-Dnovel.argon2.benchmark.*' }).Count -ne 6) {
        throw 'Argon2 Maven invocation contains an unexpected benchmark property.'
    }

    foreach ($mode in @('Missing', 'Invalid')) {
        $env:NOVEL_AUTH_BENCHMARK_MODE = $mode
        $failed = $false
        try {
            & $benchmark -MemoryKiB 19456 -Iterations 2 -Parallelism 1 -Samples 20 `
                -TargetConcurrency 4 -MavenCommand $fakeMaven | Out-Null
        } catch { $failed = $true }
        if (-not $failed) { throw "Argon2 wrapper accepted fake Maven mode: $mode" }
    }
} finally {
    $env:NOVEL_AUTH_BENCHMARK_ARGUMENTS = $previousArguments
    $env:NOVEL_AUTH_BENCHMARK_MODE = $previousMode
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}

Write-Host 'Authentication security script contracts passed.'
