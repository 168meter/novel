Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$environmentExample = Join-Path $root '.env.prod.example'
$gitIgnore = Join-Path $root '.gitignore'

if (-not (Test-Path -LiteralPath $environmentExample -PathType Leaf)) {
    throw '.env.prod.example is missing.'
}

$environmentContent = Get-Content -LiteralPath $environmentExample -Raw
$exampleLines = @($environmentContent -split "`r?`n")
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
    if ($contract.ContainsKey('ScopeBeforeKey') -and $contract.ScopeBeforeKey) {
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

    $forbiddenKeys = if ($contract.ContainsKey('ForbiddenKeys')) { @($contract.ForbiddenKeys) } else { @() }
    foreach ($forbiddenKey in $forbiddenKeys) {
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
$imageContracts = @(
    'deploy/novel-front/Dockerfile',
    'deploy/novel-front/entrypoint.sh',
    'deploy/shardingsphere/shardingsphere-jdbc.yml.template',
    '.dockerignore'
)
foreach ($relativePath in $imageContracts) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf)) {
        throw "Production front image file is missing: $relativePath"
    }
}

$dockerfileText = Get-Content -LiteralPath (Join-Path $root 'deploy/novel-front/Dockerfile') -Raw
foreach ($fragment in @(
    'FROM maven:3.9.11-eclipse-temurin-21-alpine AS build',
    'FROM eclipse-temurin:21.0.8_9-jre-alpine',
    'COPY novel-admin/pom.xml novel-admin/pom.xml',
    'COPY novel-crawl/pom.xml novel-crawl/pom.xml',
    'COPY novel-common/src novel-common/src',
    'COPY novel-front/src novel-front/src',
    'RUN apk add --no-cache gettext wget',
    'USER 10001:10001',
    'HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5',
    '! -name ''*-sources.jar''',
    '! -name ''*-javadoc.jar''',
    'test "$jar_count" -eq 1',
    'ENTRYPOINT ["/app/entrypoint.sh"]'
)) {
    if (-not $dockerfileText.Contains($fragment)) {
        throw "Hardened front Dockerfile property is missing: $fragment"
    }
}
if ($dockerfileText -match '(?im)^\s*ARG\s+.*(PASSWORD|SECRET|TOKEN|KEY)') {
    throw 'Production front Dockerfile must not accept secret build arguments.'
}

$entrypointText = Get-Content -LiteralPath (Join-Path $root 'deploy/novel-front/entrypoint.sh') -Raw
foreach ($fragment in @(
    'set -eu',
    'umask 077',
    ': "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"',
    ': "${MYSQL_USER:?MYSQL_USER is required}"',
    ': "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"',
    'yaml_quote()',
    'MYSQL_USER_YAML="$(yaml_quote "$MYSQL_USER")"',
    'MYSQL_PASSWORD_YAML="$(yaml_quote "$MYSQL_PASSWORD")"',
    'envsubst ''${MYSQL_DATABASE} ${MYSQL_USER_YAML} ${MYSQL_PASSWORD_YAML}''',
    'chmod 600 /app/runtime/shardingsphere-jdbc.yml',
    'jdbc:shardingsphere:absolutepath:/app/runtime/shardingsphere-jdbc.yml',
    'exec java -jar /app/novel-front.jar'
)) {
    if (-not $entrypointText.Contains($fragment)) {
        throw "Secure front entrypoint property is missing: $fragment"
    }
}
if ($entrypointText.Contains('exec java ${JAVA_TOOL_OPTIONS')) {
    throw 'JAVA_TOOL_OPTIONS must not be expanded explicitly by the front entrypoint.'
}

$shardingTemplate = Get-Content -LiteralPath (Join-Path $root 'deploy/shardingsphere/shardingsphere-jdbc.yml.template') -Raw
foreach ($fragment in @(
    'jdbc:mysql://mysql:3306/${MYSQL_DATABASE}',
    'allowPublicKeyRetrieval=true',
    'username: ${MYSQL_USER_YAML}',
    'password: ${MYSQL_PASSWORD_YAML}',
    'maximumPoolSize: 10',
    'minimumIdle: 2',
    'actualDataNodes: ds_1.book_content${0..9}',
    'algorithm-expression: book_content${index_id % 10}',
    'sql-show: false'
)) {
    if (-not $shardingTemplate.Contains($fragment)) {
        throw "Private ShardingSphere template property is missing: $fragment"
    }
}

$dockerIgnoreLines = @(Get-Content -LiteralPath (Join-Path $root '.dockerignore'))
foreach ($entry in @(
    '*', '!pom.xml', '!novel-common/', '!novel-common/pom.xml',
    '!novel-common/src/', '!novel-common/src/**', '!novel-front/',
    '!novel-front/pom.xml', '!novel-front/src/', '!novel-front/src/**',
    '!novel-admin/', '!novel-admin/pom.xml', '!novel-crawl/',
    '!novel-crawl/pom.xml', '!templates/', '!templates/**', '!deploy/',
    '!deploy/novel-front/', '!deploy/novel-front/Dockerfile',
    '!deploy/novel-front/entrypoint.sh', '!deploy/shardingsphere/',
    '!deploy/shardingsphere/shardingsphere-jdbc.yml.template',
    '**/src/main/resources/application*-dev.yml',
    '**/src/main/build/config/shardingsphere-jdbc.yml'
)) {
    if ($dockerIgnoreLines -notcontains $entry) {
        throw "Docker build exclusion is missing: $entry"
    }
}

$dataServiceContracts = @(
    'deploy/mysql/conf.d/novel.cnf',
    'deploy/redis/redis.conf',
    'deploy/scripts/init-database.sh'
)
foreach ($relativePath in $dataServiceContracts) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf)) {
        throw "Production data service file is missing: $relativePath"
    }
}

$mysqlSection = ''
$mysqlDirectives = @()
foreach ($rawLine in Get-Content -LiteralPath (Join-Path $root 'deploy/mysql/conf.d/novel.cnf')) {
    $line = $rawLine.Trim()
    if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^[#;]') { continue }
    if ($line -match '^\[(?<section>[^]]+)\]$') {
        $mysqlSection = $Matches['section'].ToLowerInvariant()
        continue
    }
    if ($mysqlSection -ne 'mysqld') { continue }
    $parts = @($line -split '\s*=\s*', 2)
    $mysqlDirectives += [pscustomobject]@{
        Key = $parts[0].ToLowerInvariant().Replace('-', '_')
        Value = if ($parts.Count -eq 2) { $parts[1].Trim() } else { '' }
    }
}
$expectedMysql = [ordered]@{
    'character_set_server' = 'utf8mb4'
    'collation_server' = 'utf8mb4_unicode_ci'
    'innodb_buffer_pool_size' = '384M'
    'innodb_buffer_pool_instances' = '1'
    'max_connections' = '60'
    'table_open_cache' = '400'
    'table_definition_cache' = '400'
    'tmp_table_size' = '32M'
    'max_heap_table_size' = '32M'
    'temptable_max_ram' = '64M'
    'skip_log_bin' = ''
    'performance_schema' = 'OFF'
}
if ($mysqlDirectives.Key -contains 'log_bin') {
    throw 'Production MySQL configuration must not enable the binary log.'
}
foreach ($entry in $expectedMysql.GetEnumerator()) {
    $matches = @($mysqlDirectives | Where-Object { $_.Key -eq $entry.Key })
    if ($matches.Count -ne 1 -or $matches[0].Value -cne $entry.Value) {
        throw "MySQL directive must appear exactly once with the expected value: $($entry.Key)=$($entry.Value)"
    }
}

$redisDirectives = @()
foreach ($rawLine in Get-Content -LiteralPath (Join-Path $root 'deploy/redis/redis.conf')) {
    $line = $rawLine.Trim()
    if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^#') { continue }
    $parts = @($line -split '\s+', 2)
    $redisDirectives += [pscustomobject]@{
        Key = $parts[0].ToLowerInvariant()
        Value = if ($parts.Count -eq 2) { $parts[1].Trim() } else { '' }
    }
}
$expectedRedis = @(
    @('bind', '0.0.0.0'),
    @('protected-mode', 'yes'),
    @('port', '6379'),
    @('appendonly', 'yes'),
    @('appendfsync', 'everysec'),
    @('auto-aof-rewrite-percentage', '100'),
    @('auto-aof-rewrite-min-size', '64mb'),
    @('maxmemory', '176mb'),
    @('maxmemory-policy', 'allkeys-lfu'),
    @('save', '900 1'),
    @('save', '300 10')
)
foreach ($expected in $expectedRedis) {
    $matches = @($redisDirectives | Where-Object {
        $_.Key -eq $expected[0] -and $_.Value -ceq $expected[1]
    })
    if ($matches.Count -ne 1) {
        throw "Redis directive must appear exactly once with the expected value: $($expected[0]) $($expected[1])"
    }
}
foreach ($uniqueKey in @($expectedRedis | ForEach-Object { $_[0] } | Where-Object { $_ -ne 'save' } | Select-Object -Unique)) {
    if (@($redisDirectives | Where-Object { $_.Key -eq $uniqueKey }).Count -ne 1) {
        throw "Redis directive must not be duplicated: $uniqueKey"
    }
}
if (@($redisDirectives | Where-Object { $_.Key -eq 'save' }).Count -ne 2) {
    throw 'Redis save directives must be exactly the two approved schedules.'
}
if ($redisDirectives.Key -contains 'requirepass' -or $redisDirectives.Key -contains 'masterauth') {
    throw 'Tracked Redis configuration must not contain a password.'
}

$databaseInit = Get-Content -LiteralPath (Join-Path $root 'deploy/scripts/init-database.sh') -Raw
foreach ($fragment in @(
    'set -eu',
    '--migrate-only',
    '.env.prod',
    'compose.prod.yml',
    'docker compose --env-file',
    '-f "$COMPOSE_FILE"',
    'unzip -p "$SEED_ARCHIVE" novel_plus_data.sql',
    'doc/sql/20260911_reading_daily_aggregation.sql',
    'doc/sql/20260917_authentication_security.sql',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD"',
    'query_output="$(mysql_query "$1")" || return $?',
    'validate_count table_count "$table_count"',
    'validate_count base_table_count "$base_table_count"',
    'EXPECTED_BASE_TABLE_COUNT=50',
    'base_seed_v1',
    'mark_seed_state loading',
    'mark_seed_state ready',
    'unzip -t "$SEED_ARCHIVE"',
    'mkfifo "$IMPORT_FIFO"',
    'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()',
    'Database does not contain the complete expected base schema.',
    'Database is not empty; rerun with --migrate-only.'
)) {
    if (-not $databaseInit.Contains($fragment)) {
        throw "Safe database initialization property is missing: $fragment"
    }
}
$forbiddenPasswordArgumentPattern = '(?m)(?:^|\s)(?:-p\S+|--password(?:=\S+|\s+\S+))'
foreach ($unsafeFixture in @(
    'mysql -psecret',
    'mysql --password=secret',
    'mysql --password secret'
)) {
    if ($unsafeFixture -notmatch $forbiddenPasswordArgumentPattern) {
        throw "Password argument detector failed its unsafe fixture: $unsafeFixture"
    }
}
if ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root' -match $forbiddenPasswordArgumentPattern) {
    throw 'Password argument detector rejects the approved MYSQL_PWD pattern.'
}
if ($databaseInit -match $forbiddenPasswordArgumentPattern) {
    throw 'Database initialization must not put a password on the command line.'
}

$messagingContracts = @(
    'deploy/scripts/init-kafka-topics.sh',
    'deploy/prometheus/prometheus.yml'
)
foreach ($relativePath in $messagingContracts) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf)) {
        throw "Production messaging or monitoring file is missing: $relativePath"
    }
}

$kafkaInit = Get-Content -LiteralPath (Join-Path $root 'deploy/scripts/init-kafka-topics.sh') -Raw
foreach ($fragment in @(
    'set -eu',
    '.env.prod',
    'compose.prod.yml',
    "BOOTSTRAP_SERVER='localhost:19092'",
    'kafka-broker-api-versions.sh',
    'kafka-topics.sh',
    '--create',
    '--if-not-exists',
    '--partitions 3',
    '--replication-factor 1',
    'kafka-configs.sh',
    'retention.ms=86400000,retention.bytes=134217728',
    'retention.ms=604800000,retention.bytes=268435456',
    'novel-book-visit-v1',
    'novel-book-visit-dlt',
    'novel-reading-engagement-v1',
    'novel-reading-engagement-dlt',
    'topic_partitions=',
    'topic_replication_factor=',
    '[ "$topic_partitions" = ''3'' ]',
    '[ "$topic_replication_factor" = ''1'' ]',
    'grep -F "$retention_ms sensitive=false"',
    'grep -F "$retention_bytes sensitive=false"'
)) {
    if (-not $kafkaInit.Contains($fragment)) {
        throw "Kafka topic retention property is missing: $fragment"
    }
}

$productionPrometheus = Get-Content -LiteralPath (Join-Path $root 'deploy/prometheus/prometheus.yml') -Raw
foreach ($fragment in @(
    'scrape_interval: 30s',
    'evaluation_interval: 30s',
    'job_name: novel-front',
    'metrics_path: /actuator/prometheus',
    'novel-front:8084',
    '/etc/prometheus/rules/*.yml'
)) {
    if (-not $productionPrometheus.Contains($fragment)) {
        throw "Production Prometheus property is missing: $fragment"
    }
}
if ($productionPrometheus.Contains('host.docker.internal')) {
    throw 'Production Prometheus must use Docker DNS, not host.docker.internal.'
}

$nginxContracts = @(
    'deploy/nginx/nginx.conf',
    'deploy/nginx/conf.d/novel-front.conf',
    'deploy/nginx/https-activation.example.conf'
)
foreach ($relativePath in $nginxContracts) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf)) {
        throw "Production Nginx file is missing: $relativePath"
    }
}

function Get-ActiveNginxText([string]$Path) {
    return ((Get-Content -LiteralPath $Path | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch '^\s*#'
    }) -join "`n")
}

$nginxGlobal = Get-ActiveNginxText (Join-Path $root 'deploy/nginx/nginx.conf')
foreach ($fragment in @(
    'worker_processes 2;',
    'worker_connections 1024;',
    'access_log /dev/stdout main;',
    'error_log /dev/stderr warn;',
    'include /etc/nginx/mime.types;',
    'server_tokens off;',
    'client_max_body_size 10m;',
    'limit_req_zone $binary_remote_addr zone=public_api:10m rate=20r/s;',
    'limit_req_zone $binary_remote_addr zone=static_assets:10m rate=100r/s;',
    'limit_conn_zone $binary_remote_addr zone=per_ip:10m;',
    'gzip on;',
    'text/css',
    'application/javascript',
    'application/json',
    'image/svg+xml',
    'include /etc/nginx/conf.d/*.conf;'
)) {
    if (-not $nginxGlobal.Contains($fragment)) {
        throw "Production Nginx global property is missing: $fragment"
    }
}
$globalDirectiveFamilies = @(
    '^\s*worker_processes\s+',
    '^\s*error_log\s+',
    '^\s*client_max_body_size\s+',
    '^\s*limit_conn_zone\s+'
)
foreach ($pattern in $globalDirectiveFamilies) {
    if ([regex]::Matches($nginxGlobal, $pattern, [Text.RegularExpressions.RegexOptions]::Multiline).Count -ne 1) {
        throw "Production Nginx global directive must appear exactly once: $pattern"
    }
}
if ([regex]::Matches($nginxGlobal, '^\s*limit_req_zone\s+', [Text.RegularExpressions.RegexOptions]::Multiline).Count -ne 2) {
    throw 'Production Nginx must define exactly the dynamic and static request-rate zones.'
}

$nginxFront = Get-ActiveNginxText (Join-Path $root 'deploy/nginx/conf.d/novel-front.conf')
foreach ($fragment in @(
    'listen 80 default_server;',
    'server_name _;',
    'location = /actuator { return 404; }',
    'location ^~ /actuator/ { return 404; }',
    'location ~* \.(?:css|js|mjs|png|jpe?g|gif|svg|ico|woff2?)$ {',
    'limit_req zone=public_api burst=40 nodelay;',
    'limit_conn per_ip 30;',
    'proxy_pass http://novel-front:8083;',
    'proxy_set_header Host $host;',
    'proxy_set_header X-Real-IP $remote_addr;',
    'proxy_set_header X-Forwarded-Host $host;',
    'proxy_set_header X-Forwarded-Proto $scheme;',
    'proxy_set_header X-Forwarded-For $remote_addr;',
    'proxy_connect_timeout 3s;',
    'proxy_send_timeout 30s;',
    'proxy_read_timeout 30s;',
    'proxy_cache off;'
)) {
    if (-not $nginxFront.Contains($fragment)) {
        throw "Production Nginx front property is missing: $fragment"
    }
}
$actuatorLocations = @($nginxFront -split "`n" | Where-Object { $_ -match '^\s*location\s+.*actuator' })
if ($actuatorLocations.Count -ne 2) {
    throw 'Production Nginx must have exactly two active Actuator denial locations.'
}
$dynamicLocation = [regex]::Match(
    $nginxFront,
    '(?ms)^\s*location\s+/\s*\{(?<body>.*?)^\s*\}'
)
if (-not $dynamicLocation.Success) {
    throw 'Production Nginx dynamic location block is missing.'
}
$dynamicBody = $dynamicLocation.Groups['body'].Value
foreach ($pattern in @(
    '(?m)^\s*limit_req\s+',
    '(?m)^\s*limit_conn\s+',
    '(?m)^\s*proxy_pass\s+',
    '(?m)^\s*proxy_set_header\s+Host\s+',
    '(?m)^\s*proxy_set_header\s+X-Real-IP\s+',
    '(?m)^\s*proxy_set_header\s+X-Forwarded-Host\s+',
    '(?m)^\s*proxy_set_header\s+X-Forwarded-Proto\s+',
    '(?m)^\s*proxy_set_header\s+X-Forwarded-For\s+',
    '(?m)^\s*proxy_connect_timeout\s+',
    '(?m)^\s*proxy_send_timeout\s+',
    '(?m)^\s*proxy_read_timeout\s+',
    '(?m)^\s*proxy_cache\s+'
)) {
    if ([regex]::Matches($dynamicBody, $pattern).Count -ne 1) {
        throw "Production Nginx dynamic directive must appear exactly once: $pattern"
    }
}
$staticMarker = 'location ~* \.(?:css|js|mjs|png|jpe?g|gif|svg|ico|woff2?)$ {'
$staticLocation = [regex]::Match(
    $nginxFront,
    '(?ms)^\s*' + [regex]::Escape($staticMarker) + '\s*(?<body>.*?)^\s*\}'
)
if (-not $staticLocation.Success) {
    throw 'Production Nginx static location block is missing.'
}
$staticBody = $staticLocation.Groups['body'].Value
foreach ($pattern in @(
    '(?m)^\s*limit_req\s+zone=static_assets\s+burst=200\s+nodelay;',
    '(?m)^\s*limit_conn\s+per_ip\s+30;',
    '(?m)^\s*proxy_pass\s+http://novel-front:8083;',
    '(?m)^\s*proxy_set_header\s+X-Real-IP\s+\$remote_addr;',
    '(?m)^\s*proxy_set_header\s+X-Forwarded-For\s+\$remote_addr;',
    '(?m)^\s*proxy_cache\s+off;'
)) {
    if ([regex]::Matches($staticBody, $pattern).Count -ne 1) {
        throw "Production Nginx static directive must appear exactly once: $pattern"
    }
}
if ($nginxFront.Contains('$proxy_add_x_forwarded_for')) {
    throw 'Production Nginx must replace, not append, untrusted forwarded addresses.'
}
if ($nginxFront -match '(?im)^\s*(?:listen\s+(?:\[[^]]+\]:|[^:\s]+:)?443(?:\s|;)|return\s+30[1278]\s+https://|rewrite\s+.*https://|ssl_certificate(?:_key)?\s+)') {
    throw 'Production Nginx must not activate TLS or redirects before certificates exist.'
}

$httpsExample = Get-ActiveNginxText (Join-Path $root 'deploy/nginx/https-activation.example.conf')
foreach ($fragment in @(
    'listen 443 ssl;',
    '/etc/letsencrypt/live/YOUR_DOMAIN/fullchain.pem',
    '/etc/letsencrypt/live/YOUR_DOMAIN/privkey.pem',
    'proxy_pass http://novel-front:8083;'
)) {
    if (-not $httpsExample.Contains($fragment)) {
        throw "Nginx HTTPS activation example is incomplete: $fragment"
    }
}
$httpsDynamicLocation = [regex]::Match(
    $httpsExample,
    '(?ms)^\s*location\s+/\s*\{(?<body>.*?)^\s*\}'
)
$httpsStaticLocation = [regex]::Match(
    $httpsExample,
    '(?ms)^\s*' + [regex]::Escape($staticMarker) + '\s*(?<body>.*?)^\s*\}'
)
if (-not $httpsDynamicLocation.Success -or -not $httpsStaticLocation.Success) {
    throw 'Nginx HTTPS activation example must contain both dynamic and static proxy locations.'
}
if ($httpsDynamicLocation.Groups['body'].Value.Trim() -cne $dynamicBody.Trim()) {
    throw 'Nginx HTTPS dynamic proxy boundary differs from the active HTTP boundary.'
}
if ($httpsStaticLocation.Groups['body'].Value.Trim() -cne $staticBody.Trim()) {
    throw 'Nginx HTTPS static proxy boundary differs from the active HTTP boundary.'
}
Write-Host 'Production deployment configuration contracts passed.'
