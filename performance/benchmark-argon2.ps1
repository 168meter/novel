param(
    [ValidateSet(19456, 32768, 65536)][int]$MemoryKiB = 19456,
    [ValidateSet(2, 3, 4)][int]$Iterations = 2,
    [ValidateSet(1, 2, 4)][int]$Parallelism = 1,
    [ValidateSet(20, 50, 100)][int]$Samples = 50,
    [ValidateSet(2, 4, 8, 16)][int]$TargetConcurrency = 4,
    [string]$MavenCommand = '',
    [switch]$Offline,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Resolve-MavenCommand([string]$RequestedCommand) {
    if ($RequestedCommand) {
        if (Test-Path -LiteralPath $RequestedCommand -PathType Leaf) {
            return (Resolve-Path -LiteralPath $RequestedCommand).Path
        }
        $command = Get-Command $RequestedCommand -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
        throw "Specified Maven command was not found: $RequestedCommand"
    }
    foreach ($name in @('mvn.cmd', 'mvn')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    if ($env:MAVEN_HOME) {
        $candidate = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    foreach ($pattern in @(
        'D:\IntelliJ IDEA *\plugins\maven\lib\maven3\bin\mvn.cmd'
        'C:\Program Files\JetBrains\IntelliJ IDEA *\plugins\maven\lib\maven3\bin\mvn.cmd'
        "$env:LOCALAPPDATA\Programs\IntelliJ IDEA *\plugins\maven\lib\maven3\bin\mvn.cmd"
    )) {
        $candidate = Get-Item -Path $pattern -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    throw 'Maven was not found. Add mvn to PATH, set MAVEN_HOME, or pass -MavenCommand.'
}

if (([long]$MemoryKiB * $TargetConcurrency) -gt 524288L) {
    throw 'Requested benchmark memory exceeds the 512 MiB safety budget.'
}
if ($ValidateOnly) {
    Write-Output "Argon2 benchmark parameters validated: memoryKiB=$MemoryKiB iterations=$Iterations parallelism=$Parallelism samples=$Samples concurrency=1,$TargetConcurrency"
    return
}

$maven = Resolve-MavenCommand $MavenCommand
$userProfileDirectory = if ($env:USERPROFILE) { $env:USERPROFILE } else {
    [Environment]::GetFolderPath('UserProfile')
}
$mavenRepository = Join-Path $userProfileDirectory '.m2\repository'
Push-Location $root
try {
    $arguments = @(
        '-q'
        '-Dmaven.compiler.fork=true'
        "-Dmaven.repo.local=$mavenRepository"
        '-Dmaven.test.skip=false'
        '-DskipTests=false'
        '-Dsurefire.failIfNoSpecifiedTests=false'
        '-Dtest=com.java2nb.novel.auth.password.Argon2Benchmark'
        '-Dnovel.argon2.benchmark.enabled=true'
        "-Dnovel.argon2.benchmark.memory-kib=$MemoryKiB"
        "-Dnovel.argon2.benchmark.iterations=$Iterations"
        "-Dnovel.argon2.benchmark.parallelism=$Parallelism"
        "-Dnovel.argon2.benchmark.samples=$Samples"
        "-Dnovel.argon2.benchmark.target-concurrency=$TargetConcurrency"
        '-pl'
        'novel-front'
        '-am'
        'test'
    )
    if ($Offline) { $arguments = @('-o') + $arguments }
    $global:LASTEXITCODE = 0
    $output = @(& $maven @arguments 2>&1)
    $mavenExitCode = $global:LASTEXITCODE
    if ($mavenExitCode -ne 0) {
        $failureText = ($output | ForEach-Object { $_.ToString() }) -join "`n"
        $category = if ($failureText -match 'Non-resolvable parent POM|Could not transfer artifact') {
            'dependency resolution failed'
        } elseif ($failureText -match 'AccessDeniedException|Access is denied') {
            'local file access failed'
        } elseif ($failureText -match 'COMPILATION ERROR|Compilation failure') {
            'compilation failed'
        } elseif ($failureText -match 'There are test failures|Tests run:.*Failures:') {
            'tests failed'
        } else { 'unclassified Maven failure' }
        throw "Argon2 benchmark Maven execution failed with exit code $mavenExitCode ($category)."
    }
    $pattern = '^ARGON2_BENCHMARK_RESULT operation=(encode|verify) concurrency=(\d+) samples=(\d+) p50_ms=([0-9]+(?:\.[0-9]+)?) p95_ms=([0-9]+(?:\.[0-9]+)?) throughput_ops_per_sec=([0-9]+(?:\.[0-9]+)?) failures=(\d+)$'
    $results = @($output | ForEach-Object { $_.ToString() } | Where-Object { $_ -match '^ARGON2_BENCHMARK_RESULT ' })
    if ($results.Count -ne 4) { throw 'Argon2 benchmark must emit exactly four result lines.' }
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($line in $results) {
        if ($line -notmatch $pattern) { throw 'Argon2 benchmark emitted a malformed result.' }
        $operation = $matches[1]
        $concurrency = [int]$matches[2]
        $sampleCount = [int]$matches[3]
        $p50 = [double]::Parse($matches[4], [Globalization.CultureInfo]::InvariantCulture)
        $p95 = [double]::Parse($matches[5], [Globalization.CultureInfo]::InvariantCulture)
        $throughput = [double]::Parse($matches[6], [Globalization.CultureInfo]::InvariantCulture)
        $failures = [int]$matches[7]
        if ($concurrency -notin @(1, $TargetConcurrency) -or $sampleCount -ne $Samples -or
            $p50 -lt 0 -or $p95 -lt $p50 -or $throughput -le 0 -or $failures -ne 0 -or
            -not $seen.Add("$operation/$concurrency")) {
            throw 'Argon2 benchmark result failed validation.'
        }
    }
    foreach ($required in @('encode/1', 'verify/1', "encode/$TargetConcurrency", "verify/$TargetConcurrency")) {
        if (-not $seen.Contains($required)) { throw "Argon2 benchmark result is missing: $required" }
    }
    Write-Output "ARGON2_BENCHMARK_CONFIG memory_kib=$MemoryKiB iterations=$Iterations parallelism=$Parallelism samples=$Samples concurrency=1,$TargetConcurrency"
    $results | Write-Output
} finally {
    Pop-Location
}
