param(
    [string]$BaseUrl = 'http://127.0.0.1:8083',
    [string]$MySqlContainer = 'novel-mysql',
    [string]$MySqlPassword = '123456',
    [string]$RedisContainer = 'novel-redis',
    [string]$RedisPassword = '123456',
    [string]$RedisKey = 'novel:home:reading-recommendation:v1',
    [string]$ValidateOnlySql,
    [DateTimeOffset]$Now = [DateTimeOffset]::UtcNow
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$caps = @(4, 10, 5, 6, 6)

function Write-Pass([string]$Message) {
    Write-Host "PASS: $Message" -ForegroundColor Green
}

function Assert-SafeSqlRead([string]$Query) {
    if ($Query -notmatch '^\s*(WITH|SELECT)\b' -or
        $Query -match '(?i)\b(INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE|CREATE|CALL|SET|FOR\s+UPDATE|LOCK\s+IN\s+SHARE\s+MODE|INTO\s+(OUTFILE|DUMPFILE))\b' -or
        $Query.Contains(';')) {
        throw 'Acceptance SQL must be one read-only SELECT/CTE statement.'
    }
}

function Invoke-SqlRead([string]$Query) {
    Assert-SafeSqlRead $Query
    $output = docker exec $MySqlContainer mysql --batch --raw --skip-column-names -uroot "-p$MySqlPassword" novel_plus -e $Query
    if ($LASTEXITCODE -ne 0) { throw 'Read-only MySQL query failed.' }
    return (($output | Out-String).Trim())
}

function Invoke-RedisSnapshotRead {
    $output = docker exec $RedisContainer sh -c `
        'set -o pipefail; redis-cli --no-auth-warning -a "$1" --raw GET "$2" | base64' `
        sh $RedisPassword $RedisKey
    if ($LASTEXITCODE -ne 0) { throw 'Read-only Redis snapshot GET failed.' }
    $base64 = ((@($output) | ForEach-Object { $_.ToString() }) -join '').Trim()
    try {
        $encoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($base64)).Trim()
    } catch {
        throw 'Redis recommendation snapshot transport is not valid Base64/UTF-8.'
    }
    if ([string]::IsNullOrWhiteSpace($encoded)) { throw "Redis snapshot is missing: $RedisKey" }
    return $encoded
}

function Invoke-JsonGet([string]$Path) {
    $response = Invoke-RestMethod -Uri "$BaseUrl$Path" -Method GET -TimeoutSec 10
    if ($null -eq $response) { throw "Empty response from $Path" }
    $okProperty = $response.PSObject.Properties['ok']
    if ($null -ne $okProperty -and $response.ok -ne $true) { throw "Application reported failure from $Path" }
    $dataProperty = $response.PSObject.Properties['data']
    if ($null -eq $dataProperty -or $null -eq $response.data) { throw "Response data is missing from $Path" }
    return $response.data
}

function Get-Group([object]$Groups, [string]$Type) {
    if ($Groups -is [System.Collections.IDictionary]) {
        if (-not $Groups.Contains($Type)) { throw "Recommendation group is missing: $Type" }
        return @($Groups[$Type])
    }
    $property = $Groups.PSObject.Properties[$Type]
    if ($null -eq $property) { throw "Recommendation group is missing: $Type" }
    return @($property.Value)
}

function Assert-Groups([object]$Groups, [string]$Source) {
    if ($null -eq $Groups) { throw "$Source recommendation groups are null." }
    $names = if ($Groups -is [System.Collections.IDictionary]) {
        @($Groups.Keys | ForEach-Object { $_.ToString() })
    } else {
        @($Groups.PSObject.Properties.Name)
    }
    if (@(Compare-Object @('0','1','2','3','4') $names).Count -ne 0) {
        throw "$Source must contain exactly recommendation groups 0,1,2,3,4."
    }
    for ($type = 0; $type -le 4; $type++) {
        $rows = @(Get-Group $Groups $type.ToString())
        if ($rows.Count -gt $caps[$type]) { throw "$Source group $type exceeds cap $($caps[$type])." }
        $ids = [System.Collections.Generic.HashSet[long]]::new()
        for ($slot = 0; $slot -lt $rows.Count; $slot++) {
            $row = $rows[$slot]
            if ($null -eq $row -or $null -eq $row.bookId -or [long]$row.bookId -le 0) {
                throw "$Source group $type contains a malformed book row."
            }
            if ($null -eq $row.type -or [int]$row.type -ne $type) {
                throw "$Source group $type contains a row with the wrong type."
            }
            if (-not $ids.Add([long]$row.bookId)) { throw "$Source group $type contains duplicate book ID $($row.bookId)." }
            if (($type -eq 2 -or $type -eq 3) -and ($null -eq $row.sort -or [int]$row.sort -ne ($slot + 1))) {
                throw "$Source group $type has non-sequential dynamic slots."
            }
        }
    }
}

function Get-Ids([object]$Groups, [string]$Type) {
    return @((Get-Group $Groups $Type) | ForEach-Object { [long]$_.bookId })
}

function Assert-IdsEqual([long[]]$Expected, [long[]]$Actual, [string]$Message) {
    if (($Expected -join ',') -ne ($Actual -join ',')) {
        throw "$Message Expected [$($Expected -join ',')], received [$($Actual -join ',')]."
    }
}

function Parse-Candidates([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    return @($Text -split '\r?\n' | ForEach-Object {
        $columns = $_ -split "`t"
        if ($columns.Count -ne 4) { throw 'Malformed candidate SQL output.' }
        [pscustomobject]@{
            bookId = [long]$columns[0]
            weekSeconds = [System.Numerics.BigInteger]::Parse($columns[1])
            hotSeconds = [System.Numerics.BigInteger]::Parse($columns[2])
            visitCount = [long]$columns[3]
        }
    })
}

function Parse-Configured([string]$Text) {
    $result = @{ '0' = @(); '1' = @(); '2' = @(); '3' = @(); '4' = @() }
    if ([string]::IsNullOrWhiteSpace($Text)) { return $result }
    foreach ($line in $Text -split '\r?\n') {
        $columns = $line -split "`t"
        if ($columns.Count -ne 4) { throw 'Malformed configured SQL output.' }
        $type = [int]$columns[2]
        if ($type -lt 0 -or $type -gt 4) { throw 'Configured SQL returned an invalid recommendation type.' }
        $result[$type.ToString()] += [pscustomobject]@{
            id = [long]$columns[0]; bookId = [long]$columns[1]; type = $type; sort = [int]$columns[3]
        }
    }
    return $result
}

function Add-UniqueIds(
    [System.Collections.Generic.List[long]]$Target,
    [long[]]$Ids,
    [int]$Cap,
    [System.Collections.Generic.HashSet[long]]$Excluded,
    [bool]$RequireExcluded
) {
    foreach ($id in $Ids) {
        if ($Target.Count -ge $Cap) { break }
        $isExcluded = $null -ne $Excluded -and $Excluded.Contains($id)
        if (($null -ne $Excluded) -and ($isExcluded -ne $RequireExcluded)) { continue }
        if (-not $Target.Contains($id)) { $Target.Add($id) }
    }
}

function Get-ChinaTimeZone {
    foreach ($id in @('Asia/Shanghai', 'China Standard Time')) {
        try { return [TimeZoneInfo]::FindSystemTimeZoneById($id) } catch { }
    }
    throw 'Asia/Shanghai time zone is unavailable.'
}

if ($PSBoundParameters.ContainsKey('ValidateOnlySql')) {
    Assert-SafeSqlRead $ValidateOnlySql
    Write-Pass 'SQL is one read-only SELECT/CTE statement'
    return
}

$homepage = Invoke-WebRequest -Uri "$BaseUrl/" -Method GET -UseBasicParsing -TimeoutSec 10
if ($homepage.StatusCode -ne 200) { throw "Homepage returned HTTP $($homepage.StatusCode), expected 200." }
Write-Pass 'homepage returned HTTP 200'

$snapshotJson = Invoke-RedisSnapshotRead
try { $snapshot = $snapshotJson | ConvertFrom-Json } catch { throw 'Redis recommendation snapshot is malformed JSON.' }
if ($snapshot.version -ne 1) { throw "Redis recommendation snapshot version is '$($snapshot.version)', expected 1." }
try { $generatedAt = [DateTimeOffset]::Parse($snapshot.generatedAt.ToString(), [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind) }
catch { throw 'Redis recommendation snapshot generatedAt is missing or invalid.' }
if ($generatedAt -gt $Now.AddMinutes(1) -or ($Now - $generatedAt) -gt [TimeSpan]::FromHours(24)) {
    throw 'Redis recommendation snapshot generatedAt is future-dated or older than the 24-hour local fallback bound.'
}
Assert-Groups $snapshot.groups 'Redis snapshot'
Write-Pass 'Redis snapshot version, generatedAt, groups, caps, types, slots, and duplicates are valid'

$localDate = [TimeZoneInfo]::ConvertTime($generatedAt, (Get-ChinaTimeZone)).Date
$weekStart = $localDate.AddDays(-6).ToString('yyyy-MM-dd')
$hotStart = $localDate.AddDays(-14).ToString('yyyy-MM-dd')
$endExclusive = $localDate.AddDays(1).ToString('yyyy-MM-dd')

$candidateSql = @"
WITH daily AS (
    SELECT book_id,
        SUM(CASE WHEN stat_date >= '$weekStart' THEN credited_seconds ELSE 0 END) AS week_seconds,
        SUM(credited_seconds) AS hot_seconds
    FROM book_reading_daily
    WHERE stat_date >= '$hotStart' AND stat_date < '$endExclusive'
    GROUP BY book_id
), ranked AS (
    SELECT b.id AS book_id,d.week_seconds,d.hot_seconds,COALESCE(b.visit_count,0) AS visit_count,
        ROW_NUMBER() OVER (ORDER BY d.week_seconds DESC,COALESCE(b.visit_count,0) DESC,b.id ASC) AS week_rank,
        ROW_NUMBER() OVER (ORDER BY d.hot_seconds DESC,COALESCE(b.visit_count,0) DESC,b.id ASC) AS hot_rank
    FROM daily d INNER JOIN book b ON b.id=d.book_id
    WHERE COALESCE(b.is_vip,0)=0 AND b.word_count > 0
      AND EXISTS (
          SELECT 1 FROM book_index bi INNER JOIN book_content bc ON bc.index_id=bi.id
          WHERE bi.book_id=b.id AND COALESCE(bi.is_vip,0)=0 AND CHAR_LENGTH(TRIM(bc.content)) > 0
      )
)
SELECT book_id,week_seconds,hot_seconds,visit_count
FROM ranked
WHERE (week_seconds > 0 AND week_rank <= 5) OR (hot_seconds > 0 AND hot_rank <= 11)
ORDER BY book_id
"@.Trim()

$configuredSql = @"
WITH configured AS (
    SELECT s.id,s.book_id,s.type,s.sort,
        ROW_NUMBER() OVER (PARTITION BY s.type ORDER BY s.sort,s.id) AS slot_rank
    FROM book_setting s INNER JOIN book b ON b.id=s.book_id
    WHERE s.type IN (0,1,2,3,4)
      AND (s.type IN (0,1,4) OR (
          COALESCE(b.is_vip,0)=0 AND b.word_count > 0
          AND EXISTS (
              SELECT 1 FROM book_index bi INNER JOIN book_content bc ON bc.index_id=bi.id
              WHERE bi.book_id=b.id AND COALESCE(bi.is_vip,0)=0 AND CHAR_LENGTH(TRIM(bc.content)) > 0
          )
      ))
)
SELECT id,book_id,type,sort
FROM configured
WHERE slot_rank <= CASE type WHEN 0 THEN 4 WHEN 1 THEN 10 WHEN 2 THEN 5 ELSE 6 END
ORDER BY type,sort,id
"@.Trim()

$candidateText = Invoke-SqlRead $candidateSql
$configuredText = Invoke-SqlRead $configuredSql
$candidates = @(Parse-Candidates $candidateText)
$configured = Parse-Configured $configuredText

$weekCandidates = @($candidates | Where-Object { $_.weekSeconds -gt 0 } | Sort-Object `
    @{ Expression = { $_.weekSeconds }; Descending = $true },
    @{ Expression = { $_.visitCount }; Descending = $true },
    @{ Expression = { $_.bookId }; Descending = $false })
$hotCandidates = @($candidates | Where-Object { $_.hotSeconds -gt 0 } | Sort-Object `
    @{ Expression = { $_.hotSeconds }; Descending = $true },
    @{ Expression = { $_.visitCount }; Descending = $true },
    @{ Expression = { $_.bookId }; Descending = $false })

$expected = @{ '0' = @(); '1' = @(); '2' = @(); '3' = @(); '4' = @() }
foreach ($type in @('0','1','4')) { $expected[$type] = @($configured[$type] | ForEach-Object { [long]$_.bookId }) }
$week = [System.Collections.Generic.List[long]]::new()
Add-UniqueIds -Target $week -Ids @($weekCandidates.bookId) -Cap 5 -Excluded $null -RequireExcluded $false
Add-UniqueIds -Target $week -Ids @($configured['2'] | ForEach-Object { [long]$_.bookId }) -Cap 5 -Excluded $null -RequireExcluded $false
$expected['2'] = @($week)

$weekIds = [System.Collections.Generic.HashSet[long]]::new()
foreach ($id in $week) { [void]$weekIds.Add($id) }
$hot = [System.Collections.Generic.List[long]]::new()
Add-UniqueIds -Target $hot -Ids @($hotCandidates.bookId) -Cap 6 -Excluded $weekIds -RequireExcluded $false
Add-UniqueIds -Target $hot -Ids @($configured['3'] | ForEach-Object { [long]$_.bookId }) -Cap 6 -Excluded $weekIds -RequireExcluded $false
Add-UniqueIds -Target $hot -Ids @($hotCandidates.bookId) -Cap 6 -Excluded $weekIds -RequireExcluded $true
Add-UniqueIds -Target $hot -Ids @($configured['3'] | ForEach-Object { [long]$_.bookId }) -Cap 6 -Excluded $weekIds -RequireExcluded $true
$expected['3'] = @($hot)

$homeGroups = Invoke-JsonGet '/book/listBookSetting'
Assert-Groups $homeGroups 'Homepage API'
$homepageMatchesSources = $true
foreach ($type in @('0','1','2','3','4')) {
    if (($expected[$type] -join ',') -ne ((Get-Ids $homeGroups $type) -join ',') -or
        ((Get-Ids $snapshot.groups $type) -join ',') -ne ((Get-Ids $homeGroups $type) -join ',')) {
        $homepageMatchesSources = $false
    }
}
if (-not $homepageMatchesSources) {
    $candidateTextRetry = Invoke-SqlRead $candidateSql
    $configuredTextRetry = Invoke-SqlRead $configuredSql
    $snapshotJsonRetry = Invoke-RedisSnapshotRead
    if ($candidateTextRetry -ne $candidateText -or $configuredTextRetry -ne $configuredText -or $snapshotJsonRetry -ne $snapshotJson) {
        throw 'INCONCLUSIVE: recommendation snapshot or source statistics changed before homepage comparison; retry without concurrent reading/configuration changes.'
    }
    throw 'INCONCLUSIVE: homepage does not match stable current sources, but historical generatedAt inputs cannot be reconstructed; retry against a freshly generated snapshot.'
}
Write-Pass 'homepage recommendation groups match configured and 7/15-day SQL ordering'

foreach ($path in @('/book/listClickRank','/book/listNewRank','/book/listUpdateRank')) {
    $null = Invoke-JsonGet $path
    Write-Pass "independent ranking endpoint returned data: $path"
}

$candidateTextAfter = Invoke-SqlRead $candidateSql
$configuredTextAfter = Invoke-SqlRead $configuredSql
$snapshotJsonAfter = Invoke-RedisSnapshotRead
if ($candidateTextAfter -ne $candidateText -or $configuredTextAfter -ne $configuredText -or $snapshotJsonAfter -ne $snapshotJson) {
    throw 'INCONCLUSIVE: recommendation snapshot or source statistics changed during acceptance; retry without concurrent reading/configuration changes.'
}

Write-Host 'Home recommendation read-only acceptance passed.' -ForegroundColor Green
