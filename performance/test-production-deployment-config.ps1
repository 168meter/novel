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

Write-Host 'Production deployment configuration contracts passed.'
