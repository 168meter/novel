[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [long]$BookId,

    [Parameter(Mandatory = $true)]
    [long]$BookIndexId,

    [Parameter(Mandatory = $true)]
    [ValidateSet('before', 'after')]
    [string]$Label,

    [string]$JMeterHome = 'D:\jmeter\apache-jmeter-5.6.3',

    [ValidateRange(10, 3600)]
    [int]$DurationSeconds = 60
)

$ErrorActionPreference = 'Stop'
$threadStages = @(1, 10, 30, 50, 100, 200)
$performanceRoot = $PSScriptRoot
$testPlan = Join-Path $performanceRoot 'jmeter\chapter-baseline.jmx'
$jmeter = Join-Path $JMeterHome 'bin\jmeter.bat'
$labelRoot = Join-Path $performanceRoot "results\raw\$Label"

if (-not (Test-Path -LiteralPath $jmeter -PathType Leaf)) {
    throw "JMeter executable not found: $jmeter"
}
if (-not (Test-Path -LiteralPath $testPlan -PathType Leaf)) {
    throw "JMeter test plan not found: $testPlan"
}
if (Test-Path -LiteralPath $labelRoot) {
    throw "Result directory already exists: $labelRoot. Archive it before rerunning to preserve evidence."
}

New-Item -ItemType Directory -Force -Path $labelRoot | Out-Null

Write-Host "Warm-up: bookId=$BookId bookIndexId=$BookIndexId"
$warmupResult = Join-Path $labelRoot 'warmup.jtl'
& $jmeter '-n' '-t' $testPlan '-Jthreads=1' '-JrampUp=1' '-Jduration=10' "-JbookId=$BookId" "-JbookIndexId=$BookIndexId" '-l' $warmupResult
if ($LASTEXITCODE -ne 0) {
    throw "JMeter warm-up failed with exit code $LASTEXITCODE"
}

foreach ($threads in $threadStages) {
    $stageRoot = Join-Path $labelRoot "threads-$threads"
    $resultFile = Join-Path $stageRoot 'result.jtl'
    $reportRoot = Join-Path $stageRoot 'report'
    $rampUp = [Math]::Min(30, [Math]::Max(1, [Math]::Ceiling($threads / 5)))

    New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null
    Write-Host "Running label=$Label threads=$threads rampUp=$rampUp duration=$DurationSeconds"

    & $jmeter '-n' '-t' $testPlan "-Jthreads=$threads" "-JrampUp=$rampUp" "-Jduration=$DurationSeconds" "-JbookId=$BookId" "-JbookIndexId=$BookIndexId" '-l' $resultFile '-e' '-o' $reportRoot
    if ($LASTEXITCODE -ne 0) {
        throw "JMeter stage threads=$threads failed with exit code $LASTEXITCODE"
    }
    if (-not (Test-Path -LiteralPath $resultFile -PathType Leaf)) {
        throw "JMeter did not create result file: $resultFile"
    }
}

Write-Host "Completed all stages. Results: $labelRoot"
