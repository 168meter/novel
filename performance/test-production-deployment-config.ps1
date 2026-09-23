$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$environmentExample = Join-Path $root '.env.prod.example'
$gitIgnore = Join-Path $root '.gitignore'

if (-not (Test-Path -LiteralPath $environmentExample -PathType Leaf)) {
    throw '.env.prod.example is missing.'
}

$environmentContent = Get-Content -LiteralPath $environmentExample -Raw
$requiredVariables = @(
    'MYSQL_ROOT_PASSWORD',
    'MYSQL_PASSWORD',
    'REDIS_PASSWORD',
    'GRAFANA_ADMIN_PASSWORD',
    'JWT_SECRET',
    'CACHE_MANAGER_PASSWORD',
    'NOVEL_AUTH_HMAC_SECRET',
    'NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET',
    'MAIL_USERNAME',
    'MAIL_PASSWORD',
    'PUBLIC_DOMAIN'
)

foreach ($variableName in $requiredVariables) {
    if ($environmentContent -notmatch "(?m)^$([regex]::Escape($variableName))=") {
        throw "Required production variable is missing: $variableName"
    }
}

$sensitiveVariables = @(
    'MYSQL_PASSWORD',
    'MYSQL_ROOT_PASSWORD',
    'REDIS_PASSWORD',
    'GRAFANA_ADMIN_PASSWORD',
    'JWT_SECRET',
    'CACHE_MANAGER_PASSWORD',
    'NOVEL_AUTH_HMAC_SECRET',
    'NOVEL_READING_ENGAGEMENT_IP_HMAC_SECRET',
    'MAIL_USERNAME',
    'MAIL_PASSWORD',
    'NOVEL_AI_API_KEY',
    'ALIPAY_APP_ID',
    'ALIPAY_MERCHANT_PRIVATE_KEY',
    'ALIPAY_PUBLIC_KEY',
    'ALIPAY_NOTIFY_URL',
    'ALIPAY_RETURN_URL',
    'OSS_ENDPOINT',
    'OSS_KEY_ID',
    'OSS_KEY_SECRET',
    'OSS_BUCKET_NAME',
    'OSS_WEB_URL'
)

foreach ($variableName in $sensitiveVariables) {
    $assignmentPattern = "(?m)^$([regex]::Escape($variableName))=([^`r`n]*)$"
    $assignments = [regex]::Matches($environmentContent, $assignmentPattern)
    if ($assignments.Count -eq 0) {
        throw "Required sensitive production variable is missing: $variableName"
    }
    if ($assignments.Count -gt 1) {
        throw "Sensitive production variable is declared more than once: $variableName"
    }
    if ($assignments[0].Groups[1].Value.Length -ne 0) {
        throw "Sensitive production variable must be empty: $variableName"
    }
}

$secretAssignments = $environmentContent -split "`r?`n" |
    Where-Object { $_ -match '(PASSWORD|SECRET|KEY|TOKEN)=' }
if (($secretAssignments -join "`n") -match '(?im)(=123456|=admin$|=change-me|=password$)') {
    throw 'The production environment example contains an unsafe usable default.'
}

if (-not (Test-Path -LiteralPath $gitIgnore -PathType Leaf)) {
    throw '.gitignore is missing.'
}

$gitIgnoreLines = @(Get-Content -LiteralPath $gitIgnore)
$requiredIgnoreEntries = @(
    '.env.prod',
    'deploy/runtime/',
    'deploy/backups/',
    'deploy/certbot/'
)

foreach ($ignoreEntry in $requiredIgnoreEntries) {
    if ($gitIgnoreLines -notcontains $ignoreEntry) {
        throw "Required production ignore entry is missing: $ignoreEntry"
    }
}

$forbiddenExampleVariables = @('ALIPAY_ENABLED', 'OSS_ENABLED')
foreach ($variableName in $forbiddenExampleVariables) {
    if ($exampleLines | Where-Object { $_ -match ('^' + [regex]::Escape($variableName) + '=') }) {
        throw "Unused production variable must not be documented: $variableName"
    }
}

$credentialContracts = @(
    @{
        Path = 'novel-front/src/main/resources/application-alipay.yml'
        ExpectedValues = [ordered]@{
            'app-id' = '${ALIPAY_APP_ID:}'
            'merchant-private-key' = '${ALIPAY_MERCHANT_PRIVATE_KEY:}'
            'public-key' = '${ALIPAY_PUBLIC_KEY:}'
            'notify-url' = '${ALIPAY_NOTIFY_URL:}'
            'return-url' = '${ALIPAY_RETURN_URL:}'
        }
        ForbiddenKeys = @('enabled')
    },
    @{
        Path = 'novel-front/src/main/resources/application-oss.yml'
        ExpectedValues = [ordered]@{
            'endpoint' = '${OSS_ENDPOINT:}'
            'key-id' = '${OSS_KEY_ID:}'
            'key-secret' = '${OSS_KEY_SECRET:}'
            'bucket-name' = '${OSS_BUCKET_NAME:}'
            'web-url' = '${OSS_WEB_URL:}'
        }
        ForbiddenKeys = @('enabled')
    },
    @{
        Path = 'novel-admin/src/main/resources/application-dev.yml'
        ExpectedValues = [ordered]@{
            'username' = '${NOVEL_ADMIN_DEMO_USERNAME:}'
            'password' = '${NOVEL_ADMIN_DEMO_PASSWORD:}'
        }
        ScopeBeforeKey = 'spring'
    },
    @{
        Path = 'novel-admin/src/main/resources/application-prod.yml'
        ExpectedValues = [ordered]@{
            'username' = '${NOVEL_ADMIN_DEMO_USERNAME}'
            'password' = '${NOVEL_ADMIN_DEMO_PASSWORD}'
        }
        ScopeBeforeKey = 'spring'
    }
)

foreach ($contract in $credentialContracts) {
    $configurationPath = Join-Path $root $contract.Path
    if (-not (Test-Path -LiteralPath $configurationPath -PathType Leaf)) {
        throw "Credential configuration is missing: $($contract.Path)"
    }

    $configurationLines = @(Get-Content -LiteralPath $configurationPath)
    if ($contract.ScopeBeforeKey) {
        $scopeBoundaryPattern = '^\s*' + [regex]::Escape($contract.ScopeBeforeKey) + '\s*:\s*$'
        $scopeBoundary = -1
        for ($lineIndex = 0; $lineIndex -lt $configurationLines.Count; $lineIndex++) {
            if ($configurationLines[$lineIndex] -match $scopeBoundaryPattern) {
                $scopeBoundary = $lineIndex
                break
            }
        }
        if ($scopeBoundary -lt 0) {
            throw "Credential scope is missing: $($contract.Path) [$($contract.ScopeBeforeKey)]"
        }
        $configurationLines = @($configurationLines[0..($scopeBoundary - 1)])
    }

    foreach ($entry in $contract.ExpectedValues.GetEnumerator()) {
        $keyPattern = '^\s*' + [regex]::Escape($entry.Key) + '\s*:\s*(?<value>.*)\s*$'
        $matches = @($configurationLines | Where-Object {
            $_ -notmatch '^\s*#' -and $_ -match $keyPattern
        })
        if ($matches.Count -ne 1) {
            throw "Credential key must appear exactly once: $($contract.Path) [$($entry.Key)]"
        }
        $value = ([regex]::Match($matches[0], $keyPattern).Groups['value'].Value).Trim()
        if ($value -cne $entry.Value) {
            throw "Credential key is not environment-bound: $($contract.Path) [$($entry.Key)]"
        }
    }

    foreach ($forbiddenKey in @($contract.ForbiddenKeys)) {
        if ([string]::IsNullOrWhiteSpace($forbiddenKey)) { continue }
        $forbiddenPattern = '^\s*' + [regex]::Escape($forbiddenKey) + '\s*:'
        if ($configurationLines | Where-Object {
            $_ -notmatch '^\s*#' -and $_ -match $forbiddenPattern
        }) {
            throw "Unsupported credential switch is present: $($contract.Path) [$forbiddenKey]"
        }
    }
}
function Assert-FileContainsAll {
    param(
        [Parameter(Mandatory = $true)][string] $RelativePath,
        [Parameter(Mandatory = $true)][string[]] $RequiredFragments,
        [Parameter(Mandatory = $true)][string] $ContractName
    )
    $fullPath = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "$ContractName configuration is missing: $RelativePath"
    }
    $fileText = Get-Content -LiteralPath $fullPath -Raw
    foreach ($fragment in $RequiredFragments) {
        if (-not $fileText.Contains($fragment)) {
            throw "$ContractName property is missing: $RelativePath [$fragment]"
        }
    }
}

Assert-FileContainsAll `
    -RelativePath 'novel-front/src/main/resources/application-prod.yml' `
    -ContractName 'Bounded front production' `
    -RequiredFragments @(
        'bootstrap-servers: kafka:19092',
        'max: 48',
        'min-spare: 4',
        'max-connections: 512',
        'accept-count: 100',
        'max-file-size: 10MB',
        'max-request-size: 10MB',
        'core-pool-size: 2',
        'maximum-pool-size: 6',
        'keep-alive-time: 10',
        'queue-size: 100',
        'max-poll-records: 100',
        '- 172.30.0.2',
        'cleanup-batch-size: 1000'
    )

$frontProdText = (Get-Content -LiteralPath (Join-Path $root 'novel-front/src/main/resources/application-prod.yml') -Raw) -replace "`r`n?", "`n"
$requiredFrontBlocks = @(
    "  auth:`n    trusted-proxy-addresses:`n      - 172.30.0.2`n  reading-engagement:`n    trusted-proxy-addresses:`n      - 172.30.0.2`n  kafka:`n",
    "    producer:`n      acks: all`n      key-serializer: org.apache.kafka.common.serialization.LongSerializer`n      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer`n      properties:`n        enable.idempotence: true`n        max.block.ms: 500`n        request.timeout.ms: 1000`n        delivery.timeout.ms: 3000`n",
    "    consumer:`n      enable-auto-commit: false`n      key-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer`n      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer`n      max-poll-records: 100`n",
    "    listener:`n      type: batch`n      ack-mode: batch`n",
    "    book-visit:`n      topic: novel-book-visit-v1`n      dlt-topic: novel-book-visit-dlt`n      group-id: novel-book-visit-writer-v1`n      max-poll-records: 100`n    reading-engagement:`n      topic: novel-reading-engagement-v1`n      dlt-topic: novel-reading-engagement-dlt`n      group-id: novel-reading-engagement-writer-v1`n      max-poll-records: 100`n      retry-interval: 5s`n      max-retries: 12`n      dedup-retention: 14d`n      cleanup-batch-size: 1000`n      cleanup-cron: `"0 15 3 * * *`"`n"
)
foreach ($requiredBlock in $requiredFrontBlocks) {
    if (-not $frontProdText.Contains($requiredBlock)) {
        throw 'Structured production front configuration block is missing or contains unexpected values.'
    }
}
Assert-FileContainsAll `
    -RelativePath 'novel-common/src/main/resources/application-common-prod.yml' `
    -ContractName 'Production common' `
    -RequiredFragments @(
        'host: redis',
        'port: 6379',
        'password: ${REDIS_PASSWORD}',
        'timeout: 3000ms',
        'root: info',
        'com.java2nb: info'
    )

Assert-FileContainsAll `
    -RelativePath 'novel-front/src/main/resources/application-monitoring.yml' `
    -ContractName 'Internal management' `
    -RequiredFragments @(
        'port: 8084',
        'address: 0.0.0.0',
        'include: health,prometheus,metrics',
        'show-details: never',
        'http.server.requests: true',
        'application: novel-front'
    )

$logbackPath = Join-Path $root 'novel-front/src/main/resources/logback-boot.xml'
$logbackText = Get-Content -LiteralPath $logbackPath -Raw
foreach ($fragment in @(
    '<springProfile name="prod">',
    '<springProfile name="!prod">',
    '<maxHistory>7</maxHistory>',
    '<maxFileSize>10MB</maxFileSize>',
    '<totalSizeCap>256MB</totalSizeCap>',
    '<root level="INFO">',
    '<logger name="com.java2nb" level="INFO"'
)) {
    if (-not $logbackText.Contains($fragment)) {
        throw "Bounded production logging property is missing: $fragment"
    }
}
$prodProfileMatch = [regex]::Match(
    $logbackText,
    '(?s)<springProfile name="prod">(?<body>.*?)</springProfile>'
)
if (-not $prodProfileMatch.Success) {
    throw 'Production Logback profile is missing.'
}
$nonProdProfileMatch = [regex]::Match(
    $logbackText,
    '(?s)<springProfile name="!prod">(?<body>.*?)</springProfile>'
)
if (-not $nonProdProfileMatch.Success) {
    throw 'Non-production Logback profile is missing.'
}
$nonProdBody = $nonProdProfileMatch.Groups['body'].Value
foreach ($fragment in @(
    '<maxHistory>30</maxHistory>',
    '<maxFileSize>10MB</maxFileSize>',
    '<totalSizeCap>1GB</totalSizeCap>',
    '<logger name="com.java2nb" level="DEBUG"'
)) {
    if (-not $nonProdBody.Contains($fragment)) {
        throw "Non-production Logback behavior changed: $fragment"
    }
}
if ($prodProfileMatch.Groups['body'].Value -match '(?i)level="DEBUG"') {
    throw 'Production Logback profile must not enable DEBUG logging.'
}
Write-Host 'Production deployment configuration contracts passed.'
