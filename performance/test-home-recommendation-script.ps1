$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'check-home-recommendation.ps1'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'Home recommendation acceptance script is missing.'
}

function New-Book([long]$Id, [int]$Type, [int]$Sort) {
    [pscustomobject]@{ bookId = $Id; type = $Type; sort = $Sort; bookName = "book-$Id" }
}

function New-Groups {
    [ordered]@{
        '0' = @((New-Book 1 0 1))
        '1' = @((New-Book 11 1 1))
        '2' = @((New-Book 21 2 1), (New-Book 22 2 2))
        '3' = @((New-Book 31 3 1), (New-Book 32 3 2), (New-Book 21 3 3), (New-Book 22 3 4))
        '4' = @((New-Book 41 4 1))
    }
}

function New-State([string]$Mode) {
    $groups = New-Groups
    if ($Mode -eq 'Malformed') {
        $snapshot = [ordered]@{ version = 2; generatedAt = '2026-09-14T04:00:00Z'; groups = $groups }
    } elseif ($Mode -eq 'OverCap') {
        $groups['2'] = @(21..26 | ForEach-Object { New-Book $_ 2 ($_ - 20) })
        $snapshot = [ordered]@{ version = 1; generatedAt = '2026-09-14T04:00:00Z'; groups = $groups }
    } elseif ($Mode -eq 'Duplicate') {
        $groups['3'] = @((New-Book 31 3 1), (New-Book 31 3 2))
        $snapshot = [ordered]@{ version = 1; generatedAt = '2026-09-14T04:00:00Z'; groups = $groups }
    } elseif ($Mode -eq 'WrongOrder') {
        $groups['2'] = @((New-Book 22 2 1), (New-Book 21 2 2))
        $snapshot = [ordered]@{ version = 1; generatedAt = '2026-09-14T04:00:00Z'; groups = $groups }
    } elseif ($Mode -eq 'SourceChanged') {
        $snapshot = [ordered]@{ version = 1; generatedAt = '2026-09-14T04:00:00Z'; groups = $groups }
    } else {
        $snapshot = [ordered]@{ version = 1; generatedAt = '2026-09-14T04:00:00Z'; groups = $groups }
    }
    @{
        mode = $Mode
        snapshotJson = ($snapshot | ConvertTo-Json -Depth 8 -Compress)
        groups = $groups
        calls = [System.Collections.Generic.List[string]]::new()
        candidateReads = 0
        configuredReads = 0
    }
}

function Invoke-WebRequest {
    param([string]$Uri, [string]$Method = 'GET', [switch]$UseBasicParsing, [int]$TimeoutSec)
    $global:homeRecommendationTest.calls.Add("HTTP $Method $Uri")
    if ($Method -ne 'GET' -or $Uri -ne 'http://test/') { throw 'Unexpected homepage request.' }
    [pscustomobject]@{ StatusCode = 200 }
}

function Invoke-RestMethod {
    param([string]$Uri, [string]$Method = 'GET', [int]$TimeoutSec)
    $global:homeRecommendationTest.calls.Add("JSON $Method $Uri")
    if ($Method -ne 'GET') { throw 'Acceptance HTTP calls must be GET-only.' }
    switch ($Uri) {
        'http://test/book/listBookSetting' { return [pscustomobject]@{ ok = $true; data = $global:homeRecommendationTest.groups } }
        'http://test/book/listClickRank' { return [pscustomobject]@{ ok = $true; data = @([pscustomobject]@{ id = 901 }) } }
        'http://test/book/listNewRank' { return [pscustomobject]@{ ok = $true; data = @([pscustomobject]@{ id = 902 }) } }
        'http://test/book/listUpdateRank' { return [pscustomobject]@{ ok = $true; data = @([pscustomobject]@{ id = 903 }) } }
        default { throw "Unexpected JSON endpoint: $Uri" }
    }
}

function docker {
    $arguments = @($args)
    $global:LASTEXITCODE = 0
    $joinedArguments = $arguments -join ' '
    if ($joinedArguments -match 'redis-cli') {
        if ($joinedArguments -notmatch 'GET .*novel:home:reading-recommendation:v1') {
            throw 'Redis acceptance command must be a single-key GET.'
        }
        $global:homeRecommendationTest.calls.Add('REDIS GET')
        if ($arguments -contains 'redis-cli') {
            if ($global:homeRecommendationTest.mode -eq 'ChunkedRedis') {
                return $global:homeRecommendationTest.snapshotJson.Substring(
                    0, $global:homeRecommendationTest.snapshotJson.Length - 7)
            }
            return $global:homeRecommendationTest.snapshotJson
        }
        if ($joinedArguments -notmatch '\|\s*base64') {
            throw 'Long Redis output must be Base64-wrapped inside the container.'
        }
        $base64 = [Convert]::ToBase64String(
            [Text.Encoding]::UTF8.GetBytes($global:homeRecommendationTest.snapshotJson))
        if ($global:homeRecommendationTest.mode -eq 'ChunkedRedis') {
            return @(
                $base64.Substring(0, 73),
                $base64.Substring(73, 79),
                $base64.Substring(152)
            )
        }
        return $base64
    }
    if ($arguments -contains 'mysql') {
        $sql = $arguments[-1]
        if ($sql -notmatch '^\s*(WITH|SELECT)\b' -or $sql -match '(?i)\b(INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE|CREATE|CALL|SET)\b') {
            throw "Unsafe SQL issued by acceptance: $sql"
        }
        if ($sql -match 'ROW_NUMBER\(\) OVER \(ORDER BY d\.week_seconds') {
            $global:homeRecommendationTest.candidateReads++
            $global:homeRecommendationTest.calls.Add('SQL candidates')
            if ($global:homeRecommendationTest.mode -eq 'Concurrent' -and $global:homeRecommendationTest.candidateReads -eq 2) {
                return "21`t701`t2000`t100`n22`t600`t1900`t100`n31`t0`t1800`t90`n32`t0`t1700`t80"
            }
            if ($global:homeRecommendationTest.mode -eq 'SourceChanged') {
                return "21`t700`t2000`t100`n22`t701`t1900`t100`n31`t0`t1800`t90`n32`t0`t1700`t80"
            }
            return "21`t700`t2000`t100`n22`t600`t1900`t100`n31`t0`t1800`t90`n32`t0`t1700`t80"
        }
        if ($sql -match 'ROW_NUMBER\(\) OVER \(PARTITION BY s\.type') {
            $global:homeRecommendationTest.configuredReads++
            $global:homeRecommendationTest.calls.Add('SQL configured')
            return "101`t1`t0`t1`n102`t11`t1`t1`n103`t41`t4`t1"
        }
        throw 'Unexpected SELECT issued by acceptance.'
    }
    throw 'Only Redis and MySQL Docker reads are allowed by this behavior test.'
}

$priorExitCode = $global:LASTEXITCODE
try {
    $global:homeRecommendationTest = New-State 'Good'
    & $scriptPath -BaseUrl 'http://test' -MySqlPassword 'test' -RedisPassword 'test' -Now ([datetimeoffset]'2026-09-14T04:05:00Z')
    $expectedCalls = @(
        'HTTP GET http://test/', 'REDIS GET', 'SQL candidates', 'SQL configured',
        'JSON GET http://test/book/listBookSetting', 'JSON GET http://test/book/listClickRank',
        'JSON GET http://test/book/listNewRank', 'JSON GET http://test/book/listUpdateRank',
        'SQL candidates', 'SQL configured', 'REDIS GET'
    )
    if (($global:homeRecommendationTest.calls -join '|') -ne ($expectedCalls -join '|')) {
        throw "Acceptance did not use the expected read-only sequence: $($global:homeRecommendationTest.calls -join '|')"
    }

    $global:homeRecommendationTest = New-State 'ChunkedRedis'
    & $scriptPath -BaseUrl 'http://test' -MySqlPassword 'test' -RedisPassword 'test' -Now ([datetimeoffset]'2026-09-14T04:05:00Z')

    foreach ($mode in @('Malformed', 'OverCap', 'Duplicate', 'WrongOrder')) {
        $global:homeRecommendationTest = New-State $mode
        $failed = $false
        try { & $scriptPath -BaseUrl 'http://test' -MySqlPassword 'test' -RedisPassword 'test' -Now ([datetimeoffset]'2026-09-14T04:05:00Z') } catch { $failed = $true }
        if (-not $failed) { throw "Acceptance must reject the $mode mutation." }
    }

    $global:homeRecommendationTest = New-State 'Concurrent'
    $message = ''
    try { & $scriptPath -BaseUrl 'http://test' -MySqlPassword 'test' -RedisPassword 'test' -Now ([datetimeoffset]'2026-09-14T04:05:00Z') } catch { $message = $_.Exception.Message }
    if ($message -notmatch '^INCONCLUSIVE:') {
        throw 'Concurrent statistics must be reported as inconclusive/retry, not pass or algorithm failure.'
    }

    $global:homeRecommendationTest = New-State 'SourceChanged'
    $message = ''
    try { & $scriptPath -BaseUrl 'http://test' -MySqlPassword 'test' -RedisPassword 'test' -Now ([datetimeoffset]'2026-09-14T04:05:00Z') } catch { $message = $_.Exception.Message }
    if ($message -notmatch '^INCONCLUSIVE:') {
        throw 'A stable source/snapshot mismatch must be inconclusive because historical generatedAt inputs cannot be reconstructed.'
    }

    & $scriptPath -ValidateOnlySql 'WITH source AS (SELECT 1 AS id) SELECT id FROM source'
    foreach ($unsafeSql in @(
        'SELECT * FROM book FOR UPDATE',
        'SELECT * FROM book LOCK IN SHARE MODE',
        "SELECT * FROM book INTO OUTFILE '/tmp/books.txt'",
        "SELECT * FROM book INTO DUMPFILE '/tmp/books.bin'"
    )) {
        $message = ''
        try { & $scriptPath -ValidateOnlySql $unsafeSql } catch { $message = $_.Exception.Message }
        if ($message -notmatch 'read-only') { throw "SQL guard did not reject: $unsafeSql" }
    }
    Write-Host 'Home recommendation read-only acceptance behavior passed (success plus rejection mutations).'
} finally {
    Remove-Item Function:docker,Function:Invoke-RestMethod,Function:Invoke-WebRequest
    Remove-Variable homeRecommendationTest -Scope Global -ErrorAction SilentlyContinue
    $global:LASTEXITCODE = $priorExitCode
}
