$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$schema = Join-Path $root 'doc/sql/20260917_authentication_security.sql'
$apply = Join-Path $PSScriptRoot 'apply-authentication-security-schema.ps1'
if (-not (Test-Path -LiteralPath $schema)) { throw 'Authentication migration is missing.' }
if (-not (Test-Path -LiteralPath $apply)) { throw 'Authentication apply script is missing.' }

function Assert-True($condition, $message) { if (-not $condition) { throw $message } }
function Normalize-Sql([string]$text) { ($text -replace '\s+', ' ').Trim() }
$payload = Get-Content -LiteralPath $schema -Raw -Encoding UTF8
$sql = $payload -replace '(?m)^\s*--[^\r\n]*', ''
Assert-True ($sql -notmatch '(?i)\b(DROP|TRUNCATE|DELETE|REPLACE|INSERT)\b') 'Migration contains destructive or unexpected DML.'
# Parse every statement, including quoted SQL in metadata-guarded PREPARE blocks.
$statements = @([regex]::Matches($sql, "(?s)(?:'(?:(?:'')|[^'])*'|[^';])+;") | ForEach-Object { Normalize-Sql $_.Value })
Assert-True ((Normalize-Sql ($statements -join ' ')) -eq (Normalize-Sql $sql)) 'Unparsed SQL must fail closed.'
$expected = [System.Collections.Generic.List[string]]::new()
$expected.Add('ALTER TABLE `user` MODIFY COLUMN `password` varchar(255) NOT NULL, MODIFY COLUMN `username` varchar(50) NULL;')
function Add-Guard([string]$catalog, [string]$key, [string]$name, [string]$ddl) {
    $quoted = $ddl.Replace("'", "''")
    $expected.Add("SET @auth_sql = (SELECT IF(COUNT(*) = 0, '$quoted', 'SELECT 1') FROM information_schema.$catalog WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND $key = '$name');")
    $expected.Add('PREPARE auth_statement FROM @auth_sql;')
    $expected.Add('EXECUTE auth_statement;')
    $expected.Add('DEALLOCATE PREPARE auth_statement;')
}
Add-Guard 'COLUMNS' 'COLUMN_NAME' 'password_algorithm' 'ALTER TABLE `user` ADD COLUMN `password_algorithm` varchar(20) NULL'
$expected.Add("UPDATE ``user`` SET ``password_algorithm`` = 'MD5' WHERE ``password_algorithm`` IS NULL;")
$expected.Add("ALTER TABLE ``user`` MODIFY COLUMN ``password_algorithm`` varchar(20) NOT NULL DEFAULT 'ARGON2ID';")
Add-Guard 'COLUMNS' 'COLUMN_NAME' 'email' 'ALTER TABLE `user` ADD COLUMN `email` varchar(254) NULL'
Add-Guard 'COLUMNS' 'COLUMN_NAME' 'token_version' 'ALTER TABLE `user` ADD COLUMN `token_version` bigint NOT NULL DEFAULT 0'
Add-Guard 'COLUMNS' 'COLUMN_NAME' 'email_verified_at' 'ALTER TABLE `user` ADD COLUMN `email_verified_at` datetime NULL'
$expected.Add("SET @auth_sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE ``user`` ADD UNIQUE INDEX ``key_uq_email`` (``email``)', IF(COUNT(*) = 1 AND SUM(NON_UNIQUE = 0 AND COLUMN_NAME = 'email' AND SEQ_IN_INDEX = 1 AND SUB_PART IS NULL) = 1, 'SELECT 1', 'SELECT ``AUTH_SCHEMA_ERROR_key_uq_email_must_be_single_full_email_unique``')) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'key_uq_email');")
$expected.Add('PREPARE auth_statement FROM @auth_sql;')
$expected.Add('EXECUTE auth_statement;')
$expected.Add('DEALLOCATE PREPARE auth_statement;')
Assert-True ($statements.Count -eq $expected.Count) 'Unexpected migration statement count.'
for ($i = 0; $i -lt $expected.Count; $i++) {
    Assert-True ($statements[$i] -ceq $expected[$i]) "Unsafe migration statement or order at index $i."
}

# Execute the validated SQL protocol against a strict historical-schema state machine.
# Metadata guards consult current state, so a repeat cannot recreate columns or relabel users.
$state = @{ columns = @{}; indexes = @{}; password = 'e10adc3949ba59abbe56e057f20f883e'; algorithm = $null; rows = 1 }
function Invoke-FixtureMigration {
    $pending = $null
    foreach ($statement in $statements) {
        if ($statement.StartsWith('SET @auth_sql')) {
            if ($statement.Contains('information_schema.STATISTICS')) {
                $metadata = @($state.indexes.key_uq_email | Where-Object { $null -ne $_ })
                $pending = if ($metadata.Count -eq 0) {
                    'ALTER TABLE `user` ADD UNIQUE INDEX `key_uq_email` (`email`)'
                } elseif ($metadata.Count -eq 1 -and $metadata[0].NON_UNIQUE -eq 0 -and
                    $metadata[0].COLUMN_NAME -ceq 'email' -and $metadata[0].SEQ_IN_INDEX -eq 1 -and
                    $null -eq $metadata[0].SUB_PART) {
                    'SELECT 1'
                } else {
                    'SELECT `AUTH_SCHEMA_ERROR_key_uq_email_must_be_single_full_email_unique`'
                }
                continue
            }
            $match = [regex]::Match($statement, "IF\(COUNT\(\*\) = 0, '((?:''|[^'])*)', 'SELECT 1'\).*information_schema\.(COLUMNS|STATISTICS).* (?:COLUMN_NAME|INDEX_NAME) = '([^']+)'")
            Assert-True $match.Success 'Unrecognized metadata guard.'
            $catalog = if ($match.Groups[2].Value -eq 'COLUMNS') { $state.columns } else { $state.indexes }
            $pending = if ($catalog.ContainsKey($match.Groups[3].Value)) { 'SELECT 1' } else { $match.Groups[1].Value.Replace("''", "'") }
        } elseif ($statement -eq 'EXECUTE auth_statement;') {
            Assert-True ($null -ne $pending) 'EXECUTE requires a prepared statement.'
            if ($pending -match 'ADD COLUMN `([^`]+)`') {
                $name = $Matches[1]
                Assert-True (-not $state.columns.ContainsKey($name)) 'Duplicate column on repeat.'
                $state.columns[$name] = $pending
                if ($name -eq 'password_algorithm') {
                    Assert-True ($pending -match 'varchar\(20\) NULL$') 'Legacy algorithm must start nullable without a default.'
                    $state.algorithm = $null
                }
            } elseif ($pending -match 'ADD UNIQUE INDEX `([^`]+)`') {
                $state.indexes[$Matches[1]] = @(@{ NON_UNIQUE = 0; COLUMN_NAME = 'email'; SEQ_IN_INDEX = 1; SUB_PART = $null })
            } else { Assert-True ($pending -eq 'SELECT 1') 'Unknown prepared SQL.' }
        } elseif ($statement.StartsWith('ALTER TABLE `user` MODIFY COLUMN `password`')) {
            $state.passwordLength = 255; $state.usernameNullable = $true
        } elseif ($statement.StartsWith('UPDATE')) {
            Assert-True ($state.columns.ContainsKey('password_algorithm')) 'Backfill before column creation.'
            if ($null -eq $state.algorithm) { $state.algorithm = 'MD5' }
        } elseif ($statement.StartsWith('ALTER TABLE `user` MODIFY COLUMN `password_algorithm`')) {
            Assert-True ($null -ne $state.algorithm) 'NOT NULL/default before historical backfill.'
            $state.defaultAlgorithm = 'ARGON2ID'; $state.algorithmNullable = $false
        } elseif ($statement.StartsWith('DEALLOCATE')) { $pending = $null }
        else {
            Assert-True ($statement -eq 'PREPARE auth_statement FROM @auth_sql;') 'Unknown statement.'
            if ($pending -eq 'SELECT `AUTH_SCHEMA_ERROR_key_uq_email_must_be_single_full_email_unique`') {
                throw 'AUTH_SCHEMA_ERROR_key_uq_email_must_be_single_full_email_unique'
            }
        }
    }
}
Invoke-FixtureMigration
Invoke-FixtureMigration
Assert-True ($state.rows -eq 1 -and $state.password -ceq 'e10adc3949ba59abbe56e057f20f883e') 'Historical data changed.'
Assert-True ($state.algorithm -eq 'MD5' -and $state.defaultAlgorithm -eq 'ARGON2ID' -and -not $state.algorithmNullable) 'Incorrect historical/default algorithms.'
Assert-True ($state.passwordLength -eq 255 -and $state.usernameNullable -and $state.indexes.key_uq_email) 'Required schema missing.'
$state.algorithm = 'ARGON2ID'; $state.password = 'upgraded-hash'
Invoke-FixtureMigration
Assert-True ($state.algorithm -eq 'ARGON2ID' -and $state.password -eq 'upgraded-hash') 'Repeat migration relabels or overwrites upgraded credentials.'

# Existing names are insufficient: all metadata rows must describe exactly one full email column.
$indexCases = @(
    @{ name = 'non-unique'; rows = @(@{ NON_UNIQUE = 1; COLUMN_NAME = 'email'; SEQ_IN_INDEX = 1; SUB_PART = $null }) },
    @{ name = 'wrong-column'; rows = @(@{ NON_UNIQUE = 0; COLUMN_NAME = 'username'; SEQ_IN_INDEX = 1; SUB_PART = $null }) },
    @{ name = 'composite'; rows = @(@{ NON_UNIQUE = 0; COLUMN_NAME = 'email'; SEQ_IN_INDEX = 1; SUB_PART = $null }, @{ NON_UNIQUE = 0; COLUMN_NAME = 'username'; SEQ_IN_INDEX = 2; SUB_PART = $null }) },
    @{ name = 'wrong-sequence'; rows = @(@{ NON_UNIQUE = 0; COLUMN_NAME = 'email'; SEQ_IN_INDEX = 2; SUB_PART = $null }) },
    @{ name = 'prefix-column'; rows = @(@{ NON_UNIQUE = 0; COLUMN_NAME = 'email'; SEQ_IN_INDEX = 1; SUB_PART = 10 }) }
)
$incorrectlyAccepted = @()
foreach ($case in $indexCases) {
    $state.indexes.key_uq_email = $case.rows
    $before = ConvertTo-Json -InputObject $state.indexes.key_uq_email -Compress
    $failed = $false
    try { Invoke-FixtureMigration } catch {
        Assert-True ($_.Exception.Message -eq 'AUTH_SCHEMA_ERROR_key_uq_email_must_be_single_full_email_unique') 'Unexpected failure instead of explicit index validation.'
        $failed = $true
    }
    if (-not $failed) { $incorrectlyAccepted += $case.name }
    Assert-True ((ConvertTo-Json -InputObject $state.indexes.key_uq_email -Compress) -ceq $before) 'Migration modified an incompatible existing index.'
}
Assert-True ($incorrectlyAccepted.Count -eq 0) "Migration accepted incompatible email indexes: $($incorrectlyAccepted -join ', ')."
$state.indexes.key_uq_email = @(@{ NON_UNIQUE = 0; COLUMN_NAME = 'email'; SEQ_IN_INDEX = 1; SUB_PART = $null })
Invoke-FixtureMigration

$bootstrap = Get-Content -LiteralPath (Join-Path $root 'doc/sql/novel_plus.sql') -Raw -Encoding UTF8
$userTable = [regex]::Match($bootstrap, '(?s)CREATE TABLE `user`\s*\(.*?\) ENGINE').Value
foreach ($pattern in @('`password`\s+varchar\(255\)', '`username`\s+varchar\(50\)\s+(?:DEFAULT NULL|NULL)', "``password_algorithm``\s+varchar\(20\)\s+NOT NULL DEFAULT 'ARGON2ID'", 'UNIQUE KEY `key_uq_email` \(`email`\)')) {
    Assert-True ($userTable -match $pattern) 'Bootstrap schema diverges from authentication migration.'
}
$seedRows = @([regex]::Matches($bootstrap, '(?s)INSERT INTO `user`.*?;'))
Assert-True ($seedRows.Count -gt 0) 'Historical seed fixture missing.'
foreach ($row in $seedRows) { Assert-True ($row.Value -match "'MD5'") 'Historical bootstrap users must be explicitly labelled MD5.' }

$global:authSchemaFixture = @{ mode = 'Healthy'; calls = @(); payload = '' }
function docker {
    $arguments = @($args)
    $global:authSchemaFixture.calls += ,$arguments
    $global:LASTEXITCODE = 0
    if ($arguments[0] -eq 'inspect') {
        if ($global:authSchemaFixture.mode -eq 'Unhealthy') { return 'starting' }
        if ($global:authSchemaFixture.mode -eq 'InspectFailed') { $global:LASTEXITCODE = 1; return 'healthy' }
        return 'healthy'
    }
    Assert-True ($OutputEncoding.WebName -eq 'utf-8') 'SQL pipe must use UTF-8.'
    $global:authSchemaFixture.payload = $input | Out-String
    if ($global:authSchemaFixture.mode -eq 'SqlFailed') { $global:LASTEXITCODE = 1 }
}
$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = 'fixture-secret-never-print'
    $output = (& $apply 6>&1 | Out-String)
    Assert-True ($output -notmatch 'fixture-secret') 'Apply script leaked credentials.'
    Assert-True ($global:authSchemaFixture.calls.Count -eq 2) 'Expected health guard and SQL execution.'
    Assert-True (($global:authSchemaFixture.calls[1] -join ' ') -eq 'exec -i -e MYSQL_PWD novel-mysql mysql --default-character-set=utf8mb4 --batch --user=root --database=novel_plus') 'Unsafe execution arguments.'
    Assert-True ($global:authSchemaFixture.payload.Trim() -ceq $payload.Trim()) 'Wrong SQL payload.'
    foreach ($mode in @('Unhealthy', 'InspectFailed', 'SqlFailed', 'InvalidDatabase', 'InvalidContainer', 'InvalidUser', 'MissingPassword')) {
        $global:authSchemaFixture.mode = $mode; $global:authSchemaFixture.calls = @()
        $parameters = @{}
        if ($mode -eq 'InvalidDatabase') { $parameters.Database = 'novel_plus; DROP TABLE user' }
        if ($mode -eq 'InvalidContainer') { $parameters.Container = '--privileged' }
        if ($mode -eq 'InvalidUser') { $parameters.User = '-uroot' }
        if ($mode -eq 'MissingPassword') { $env:MYSQL_PWD = $null }
        $failed = $false
        try { & $apply @parameters 6>$null } catch { $failed = $true }
        Assert-True $failed "Expected closed failure: $mode."
        $expectedCalls = if ($mode -eq 'SqlFailed') { 2 } elseif ($mode -in @('Unhealthy','InspectFailed')) { 1 } else { 0 }
        Assert-True ($global:authSchemaFixture.calls.Count -eq $expectedCalls) "SQL executed after invalid precondition: $mode."
    }
} finally {
    $env:MYSQL_PWD = $previousPassword
    Remove-Item Function:docker
    Remove-Variable authSchemaFixture -Scope Global
}
Write-Host 'PASS: strict SQL/order, repeated historical/upgraded-state migration, exact unique email index and five incompatible-index failures, bootstrap seeds, apply guards and failures.'
Write-Host 'ONLINE MYSQL INTEGRATION NOT RUN: fixture validation does not replace running this migration twice against a disposable MySQL database.'
