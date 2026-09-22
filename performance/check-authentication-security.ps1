[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8083',
    [string]$ManagementUrl = 'http://127.0.0.1:8084',
    [string]$MailpitUrl = 'http://127.0.0.1:8025',
    [string]$MySqlContainer = 'novel-mysql',
    [string]$MySqlDatabase = 'novel_plus',
    [string]$MySqlPassword = '123456',
    [string]$RedisContainer = 'novel-redis',
    [string]$RedisPassword = '123456',
    [string]$AuthHmacSecret = $env:NOVEL_AUTH_HMAC_SECRET,
    [ValidateSet('None', 'Redis', 'MySql', 'Kafka', 'Smtp')]
    [string]$FailureDrill = 'None',
    [switch]$AllowContainerStop,
    [switch]$ValidateOnly,
    [switch]$RunBehaviorSelfTest
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$ManagementUrl = $ManagementUrl.TrimEnd('/')
$MailpitUrl = $MailpitUrl.TrimEnd('/')
$createdUsers = [System.Collections.Generic.List[object]]::new()
$mailMessageIds = [System.Collections.Generic.HashSet[string]]::new()
$stoppedContainers = [System.Collections.Generic.List[string]]::new()
$registrationSubjectTerm = [string]::Concat([char]0x6CE8, [char]0x518C)
$resetSubjectTerm = [string]::Concat([char]0x91CD, [char]0x7F6E)

function Assert-LoopbackHttpUrl([string]$Value, [string]$Name) {
    $parsed = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -ne 'http' -or $parsed.Host -notin @('127.0.0.1', 'localhost', '::1')) {
        throw "$Name must be an absolute loopback HTTP URL."
    }
}

Assert-LoopbackHttpUrl $BaseUrl 'BaseUrl'
Assert-LoopbackHttpUrl $ManagementUrl 'ManagementUrl'
Assert-LoopbackHttpUrl $MailpitUrl 'MailpitUrl'
if ($FailureDrill -ne 'None' -and -not $AllowContainerStop) {
    throw 'Failure drills require the explicit -AllowContainerStop switch.'
}
if ($ValidateOnly) {
    Write-Output 'Authentication acceptance parameters validated.'
    return
}
if (-not $RunBehaviorSelfTest -and [string]::IsNullOrWhiteSpace($AuthHmacSecret)) {
    throw 'NOVEL_AUTH_HMAC_SECRET must be available in this PowerShell process for exact Redis-key verification.'
}

function Invoke-AppRequest([string]$Path, [string]$Method = 'GET', [hashtable]$Body = @{},
                           [hashtable]$Headers = @{}, [int]$TimeoutSec = 30) {
    $request = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $Headers
        TimeoutSec = $TimeoutSec
        UseBasicParsing = $true
    }
    if ($Method -ne 'GET') {
        $request.ContentType = 'application/x-www-form-urlencoded'
        $request.Body = $Body
    }
    try {
        $response = Invoke-WebRequest @request
        $content = $response.Content
        $status = [int]$response.StatusCode
    }
    catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) { throw "Application request failed without an HTTP response: $Path" }
        $status = [int]$webResponse.StatusCode
        $content = ''
        try {
            if ($webResponse.PSObject.Properties['Content'] -and $webResponse.Content -and
                $webResponse.Content.PSObject.Methods['ReadAsStringAsync']) {
                $content = $webResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            }
            elseif ($webResponse.PSObject.Methods['GetResponseStream']) {
                $stream = $webResponse.GetResponseStream()
                if ($stream) {
                    $reader = [IO.StreamReader]::new($stream)
                    try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
                }
            }
        } catch { $content = '' }
    }
    $json = $null
    if ($content) {
        try { $json = $content | ConvertFrom-Json }
        catch { throw "Application returned malformed JSON: $Path" }
    }
    return [pscustomobject]@{ Status = $status; Json = $json }
}

function Assert-AppCode($Response, [int]$Code, [string]$Description) {
    if ($null -eq $Response.Json -or [int]$Response.Json.code -ne $Code) {
        $actual = if ($null -eq $Response.Json) { 'no-json' } else { [string]$Response.Json.code }
        throw "$Description returned code $actual; expected $Code."
    }
}

function Invoke-MySql([string]$Sql, [switch]$AllowEmpty) {
    $output = docker exec -e "MYSQL_PWD=$MySqlPassword" $MySqlContainer `
        mysql -uroot -N -s $MySqlDatabase -e $Sql 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Local MySQL command failed; SQL and credentials withheld.' }
    $text = (($output | Out-String).Trim())
    if (-not $AllowEmpty -and [string]::IsNullOrWhiteSpace($text)) {
        throw 'Local MySQL query returned no data.'
    }
    return $text
}

function Invoke-Redis([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments) {
    $output = docker exec -e "REDISCLI_AUTH=$RedisPassword" $RedisContainer `
        redis-cli --no-auth-warning @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Local Redis command failed; key and credentials withheld.' }
    return (($output | Out-String).Trim())
}

function Get-HmacHex([string]$InputText) {
    $hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($AuthHmacSecret))
    try {
        $bytes = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($InputText))
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    }
    finally { $hmac.Dispose() }
}

function Get-CaptchaKey([string]$Purpose, [string]$Email) {
    $normalized = $Email.Trim().ToLowerInvariant()
    $digest = Get-HmacHex ("email-key`0$Purpose`0$normalized")
    return "auth:captcha:$($Purpose.ToLowerInvariant().Replace('_', '-')):$($digest.Substring(0, 32))"
}

function Get-Md5Hex([string]$Value) {
    $md5 = [Security.Cryptography.MD5]::Create()
    try {
        $hex = [BitConverter]::ToString($md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))
        return $hex.Replace('-', '').ToLowerInvariant()
    }
    finally { $md5.Dispose() }
}

function Find-MailCode($SearchResult, [string]$Email) {
    foreach ($message in @($SearchResult.messages)) {
        $recipientMatches = @($message.To | Where-Object {
            [string]::Equals([string]$_.Address, $Email, [StringComparison]::OrdinalIgnoreCase)
        }).Count -gt 0
        if (-not $recipientMatches) { continue }

        $match = [regex]::Match([string]$message.Snippet, '(?<!\d)\d{6}(?!\d)')
        if ($match.Success) {
            return [pscustomobject]@{ Code = $match.Value; Id = [string]$message.ID }
        }
    }
    return $null
}

function Wait-MailCode([string]$Email, [string]$SubjectTerm, [int]$TimeoutSec = 45) {
    $query = [Uri]::EscapeDataString("to:`"$Email`" subject:`"$SubjectTerm`"")
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        try {
            $search = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/search?query=$query" -TimeoutSec 5
            $found = Find-MailCode $search $Email
            if ($null -ne $found) {
                if ($found.Id) { [void]$mailMessageIds.Add($found.Id) }
                return $found.Code
            }
        } catch { }
        Start-Sleep -Milliseconds 250
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSec)
    throw 'Timed out waiting for the isolated Mailpit verification message.'
}

function Get-MetricValue([string]$Name, [string]$Tag = '') {
    $uri = "$ManagementUrl/actuator/metrics/$([Uri]::EscapeDataString($Name))"
    if ($Tag) { $uri += '?tag=' + [Uri]::EscapeDataString($Tag) }
    try { $metric = Invoke-RestMethod -Uri $uri -TimeoutSec 5 }
    catch { throw "Authentication metric is unavailable: $Name" }
    $measurement = @($metric.measurements | Where-Object { $_.statistic -in @('COUNT', 'TOTAL_TIME', 'VALUE') } |
        Select-Object -First 1)
    if ($measurement.Count -ne 1) { throw "Authentication metric has no usable measurement: $Name" }
    return [double]$measurement[0].value
}

function Wait-ApplicationHealth([int]$TimeoutSec = 60, [int]$RequestTimeoutSec = 15,
                                [int]$PollDelayMilliseconds = 1000,
                                [scriptblock]$HealthRequest = $null) {
    if ($null -eq $HealthRequest) {
        $HealthRequest = {
            param($requestTimeout)
            Invoke-RestMethod -Uri "$ManagementUrl/actuator/health" -TimeoutSec $requestTimeout
        }
    }

    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        try {
            $health = & $HealthRequest $RequestTimeoutSec
            if ($null -ne $health -and $health.status -eq 'UP') { return $health }
        }
        catch { }
        if ($PollDelayMilliseconds -gt 0) { Start-Sleep -Milliseconds $PollDelayMilliseconds }
    } while ($timer.Elapsed.TotalSeconds -lt $TimeoutSec)

    throw "novel-front health did not become UP within $TimeoutSec seconds."
}

function Get-ContainerState([string]$Container) {
    $state = docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' `
        $Container 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect required local container: $Container" }
    return "$state".Trim()
}

function Stop-LocalContainer([string]$Container) {
    if ((Get-ContainerState $Container) -ne 'running healthy') {
        throw "Failure drill requires a healthy local container: $Container"
    }
    docker stop --time 10 $Container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not stop local container: $Container" }
    $stoppedContainers.Add($Container)
}

function Restore-LocalContainer([string]$Container) {
    docker start $Container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "CRITICAL: manually restart $Container immediately." }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-ContainerState $Container) -eq 'running healthy') {
            [void]$stoppedContainers.Remove($Container)
            Write-Host "RESTORED: $Container running healthy."
            return
        }
        Start-Sleep -Seconds 2
    } while ($timer.Elapsed.TotalSeconds -lt 120)
    throw "Container restarted but did not become healthy: $Container"
}

function Restore-TrackedContainers([scriptblock]$RestoreAction) {
    foreach ($container in @($stoppedContainers.ToArray())) {
        try { & $RestoreAction $container }
        catch { Write-Warning $_.Exception.Message }
    }
}

function Remove-TrackedUsers([scriptblock]$DeleteAction) {
    if ($createdUsers.Count -eq 0) { return }

    $clauses = [System.Collections.Generic.List[string]]::new()
    foreach ($user in $createdUsers) {
        if (-not [string]::IsNullOrWhiteSpace([string]$user.Email)) {
            $clauses.Add("(id=$($user.Id) AND email='$($user.Email)')")
        }
        else {
            $clauses.Add("(id=$($user.Id) AND username='$($user.Username)')")
        }
    }
    & $DeleteAction ("DELETE FROM user WHERE " + ($clauses -join ' OR '))
}

function New-TestIdentity {
    $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
    return [pscustomobject]@{
        Email = "auth-$suffix@example.test"
        Password = "Auth!$suffix"
        NewPassword = "Changed!$suffix"
        ClientIp = "198.51.100.$((Get-Random -Minimum 10 -Maximum 240))"
    }
}

function Add-LegacyFixture {
    $digits = Get-Random -Minimum 100000000 -Maximum 999999999
    $username = "19$digits"
    $password = "Legacy!$digits"
    $hash = Get-Md5Hex $password
    $id = [long](900000000000000000L + (Get-Random -Minimum 100000000 -Maximum 999999999))
    $sql = "INSERT INTO user(id,username,password,email,password_algorithm,token_version,email_verified_at,nick_name,account_balance,status,create_time,update_time) VALUES ($id,'$username','$hash',NULL,'MD5',0,NULL,'auth acceptance',0,0,NOW(),NOW())"
    [void](Invoke-MySql $sql -AllowEmpty)
    $createdUsers.Add([pscustomobject]@{ Id = $id; Email = $null; Username = $username })
    return [pscustomobject]@{ Username = $username; Password = $password; Id = $id }
}

function Assert-CaptchaKeyAgreement {
    $probe = New-TestIdentity
    $existingEmailCount = Invoke-MySql "SELECT COUNT(*) FROM user WHERE email='$($probe.Email)'"
    if ($existingEmailCount -ne '0') {
        throw 'Generated HMAC probe email already exists; refusing to continue.'
    }

    $headers = @{ 'X-Real-IP' = $probe.ClientIp }
    $issued = Invoke-AppRequest '/user/register/email-code' 'POST' @{ email = $probe.Email } $headers
    Assert-AppCode $issued 200 'HMAC agreement captcha request'
    $code = Wait-MailCode $probe.Email $registrationSubjectTerm
    $key = Get-CaptchaKey 'REGISTER' $probe.Email
    if ((Invoke-Redis EXISTS $key) -ne '1') {
        throw 'Computed captcha key does not match the key issued by the application.'
    }

    $registered = Invoke-AppRequest '/user/register' 'POST' @{
        email = $probe.Email; code = $code; password = $probe.Password
        confirmPassword = $probe.Password
    } $headers
    Assert-AppCode $registered 200 'HMAC agreement probe registration'
    $registeredIdText = Invoke-MySql "SELECT id FROM user WHERE email='$($probe.Email)'"
    $registeredId = 0L
    if (-not [long]::TryParse($registeredIdText, [ref]$registeredId) -or $registeredId -le 0) {
        throw 'HMAC agreement probe user could not be resolved to one exact database ID.'
    }
    $createdUsers.Add([pscustomobject]@{ Id = $registeredId; Email = $probe.Email; Username = $null })
    if ((Invoke-Redis EXISTS $key) -ne '0') {
        throw 'HMAC agreement probe captcha was not consumed.'
    }
    return $true
}

function Invoke-FailureDrill($Legacy) {
    $container = switch ($FailureDrill) {
        'Redis' { 'novel-redis' }
        'MySql' { 'novel-mysql' }
        'Kafka' { 'novel-kafka' }
        'Smtp' { 'novel-mailpit' }
    }
    $identity = New-TestIdentity
    $hmacAgreementProven = $false
    if ($FailureDrill -eq 'Smtp') {
        $hmacAgreementProven = Assert-CaptchaKeyAgreement
        if (-not $hmacAgreementProven) {
            throw 'SMTP failure drill requires a proven captcha HMAC agreement.'
        }
    }
    Stop-LocalContainer $container
    try {
        switch ($FailureDrill) {
            'Redis' {
                $response = Invoke-AppRequest '/user/register/email-code' 'POST' `
                    @{ email = $identity.Email } @{ 'X-Real-IP' = $identity.ClientIp }
                Assert-AppCode $response 1011 'Redis failure drill'
            }
            'MySql' {
                $response = Invoke-AppRequest '/user/register/email-code' 'POST' `
                    @{ email = $identity.Email } @{ 'X-Real-IP' = $identity.ClientIp } 45
                Assert-AppCode $response 1011 'MySQL failure drill'
            }
            'Kafka' {
                $response = Invoke-AppRequest '/user/login' 'POST' `
                    @{ loginAccount = $Legacy.Username; password = $Legacy.Password } `
                    @{ 'X-Real-IP' = $identity.ClientIp }
                Assert-AppCode $response 200 'Kafka independence drill'
            }
            'Smtp' {
                if (-not $hmacAgreementProven) {
                    throw 'SMTP failure drill cannot inspect revocation without a proven HMAC agreement.'
                }
                $response = Invoke-AppRequest '/user/register/email-code' 'POST' `
                    @{ email = $identity.Email } @{ 'X-Real-IP' = $identity.ClientIp }
                Assert-AppCode $response 200 'SMTP failure public response'
                $key = Get-CaptchaKey 'REGISTER' $identity.Email
                $timer = [Diagnostics.Stopwatch]::StartNew()
                do {
                    if ((Invoke-Redis EXISTS $key) -eq '0') { break }
                    Start-Sleep -Milliseconds 250
                } while ($timer.Elapsed.TotalSeconds -lt 10)
                if ((Invoke-Redis EXISTS $key) -ne '0') {
                    throw 'SMTP failure did not revoke the issued captcha within 10 seconds.'
                }
            }
        }
        Write-Host "PASS: authentication failure drill $FailureDrill matched its fail-safe contract."
    }
    finally { Restore-LocalContainer $container }
}

if ($RunBehaviorSelfTest) {
    $createdUsers.Add([pscustomobject]@{ Id = 101L; Email = 'owned@example.test'; Username = $null })
    $createdUsers.Add([pscustomobject]@{ Id = 202L; Email = $null; Username = 'owned-user' })
    $stoppedContainers.Add('fake-redis')
    $stoppedContainers.Add('fake-mailpit')

    $restored = [System.Collections.Generic.List[string]]::new()
    Restore-TrackedContainers { param($container) [void]$restored.Add($container) }
    if ($restored.Count -ne 2 -or $restored[0] -ne 'fake-redis' -or $restored[1] -ne 'fake-mailpit') {
        throw 'Container recovery behavior self-test failed.'
    }

    $deleteStatements = [System.Collections.Generic.List[string]]::new()
    Remove-TrackedUsers { param($sql) [void]$deleteStatements.Add($sql) }
    $expectedDelete = "DELETE FROM user WHERE (id=101 AND email='owned@example.test') OR (id=202 AND username='owned-user')"
    if ($deleteStatements.Count -ne 1 -or $deleteStatements[0] -ne $expectedDelete) {
        throw 'Owned-user cleanup behavior self-test failed.'
    }

    $fakeHealthResponses = [System.Collections.Generic.Queue[object]]::new()
    $fakeHealthResponses.Enqueue([pscustomobject]@{ status = 'DOWN' })
    $fakeHealthResponses.Enqueue([pscustomobject]@{ status = 'UP' })
    $fakeHealth = Wait-ApplicationHealth -TimeoutSec 1 -RequestTimeoutSec 15 `
        -PollDelayMilliseconds 0 -HealthRequest { param($requestTimeout) $fakeHealthResponses.Dequeue() }
    if ($fakeHealth.status -ne 'UP' -or $fakeHealthResponses.Count -ne 0) {
        throw 'Application health retry behavior self-test failed.'
    }

    $fakeSearch = [pscustomobject]@{ messages = @([pscustomobject]@{
        ID = 'mail-101'
        To = @([pscustomobject]@{ Address = 'owned@example.test' })
        Subject = 'misdecoded-subject-from-windows-powershell'
        Snippet = 'Verification code: 654321; valid for 10 minutes.'
    }) }
    $mailCode = Find-MailCode $fakeSearch 'owned@example.test'
    if ($null -eq $mailCode -or $mailCode.Code -ne '654321' -or $mailCode.Id -ne 'mail-101') {
        throw 'Mailpit search-result parsing behavior self-test failed.'
    }

    Write-Output 'Authentication acceptance cleanup and recovery behavior passed.'
    return
}

try {
    $health = Wait-ApplicationHealth
    $mailHealth = Invoke-WebRequest -UseBasicParsing -Uri "$MailpitUrl/readyz" -TimeoutSec 5
    if ([int]$mailHealth.StatusCode -ne 200) { throw 'Mailpit readiness did not return HTTP 200.' }

    $legacy = Add-LegacyFixture
    if ($FailureDrill -ne 'None') {
        Invoke-FailureDrill $legacy
        return
    }

    $identity = New-TestIdentity
    $existingEmailCount = Invoke-MySql "SELECT COUNT(*) FROM user WHERE email='$($identity.Email)'"
    if ($existingEmailCount -ne '0') {
        throw 'Generated registration email already exists; refusing to run destructive cleanup.'
    }
    $headers = @{ 'X-Real-IP' = $identity.ClientIp }
    $issued = Invoke-AppRequest '/user/register/email-code' 'POST' @{ email = $identity.Email } $headers
    Assert-AppCode $issued 200 'Registration captcha request'
    $limited = Invoke-AppRequest '/user/register/email-code' 'POST' @{ email = $identity.Email } $headers
    Assert-AppCode $limited 1010 'Registration captcha cooldown'
    $code = Wait-MailCode $identity.Email $registrationSubjectTerm
    $captchaKey = Get-CaptchaKey 'REGISTER' $identity.Email
    $ttl = [int](Invoke-Redis TTL $captchaKey)
    if ($ttl -lt 540 -or $ttl -gt 600) { throw "Registration captcha TTL is outside 540..600 seconds: $ttl" }

    $registered = Invoke-AppRequest '/user/register' 'POST' @{
        email = $identity.Email; code = $code; password = $identity.Password
        confirmPassword = $identity.Password
    } $headers
    Assert-AppCode $registered 200 'Email registration'
    $oldToken = [string]$registered.Json.data.token
    if ([string]::IsNullOrWhiteSpace($oldToken)) { throw 'Registration did not return a JWT.' }
    $registeredIdText = Invoke-MySql "SELECT id FROM user WHERE email='$($identity.Email)'"
    $registeredId = 0L
    if (-not [long]::TryParse($registeredIdText, [ref]$registeredId) -or $registeredId -le 0) {
        throw 'Registered user could not be resolved to one exact database ID; evidence was retained.'
    }
    $createdUsers.Add([pscustomobject]@{ Id = $registeredId; Email = $identity.Email; Username = $null })
    if ((Invoke-Redis EXISTS $captchaKey) -ne '0') { throw 'Consumed registration captcha still exists.' }
    $databaseState = Invoke-MySql "SELECT CONCAT_WS('|',password_algorithm,password LIKE '`$argon2id`$%',username IS NULL) FROM user WHERE email='$($identity.Email)'"
    if ($databaseState -ne 'ARGON2ID|1|1') { throw 'New user was not stored as email-only Argon2id.' }
    $replay = Invoke-AppRequest '/user/register' 'POST' @{
        email = $identity.Email; code = $code; password = $identity.Password
        confirmPassword = $identity.Password
    } $headers
    Assert-AppCode $replay 1009 'Registration captcha replay'

    $legacyHeaders = @{ 'X-Real-IP' = '198.51.100.241' }
    $firstLegacy = Invoke-AppRequest '/user/login' 'POST' `
        @{ loginAccount = $legacy.Username; password = $legacy.Password } $legacyHeaders
    Assert-AppCode $firstLegacy 200 'First legacy login'
    $legacyState = Invoke-MySql "SELECT CONCAT_WS('|',password_algorithm,password LIKE '`$argon2id`$%') FROM user WHERE username='$($legacy.Username)'"
    if ($legacyState -ne 'ARGON2ID|1') { throw 'Legacy login did not migrate MD5 to Argon2id.' }
    $secondLegacy = Invoke-AppRequest '/user/login' 'POST' `
        @{ loginAccount = $legacy.Username; password = $legacy.Password } $legacyHeaders
    Assert-AppCode $secondLegacy 200 'Second legacy Argon2id login'

    $resetHeaders = @{ 'X-Real-IP' = '198.51.100.242' }
    $resetIssued = Invoke-AppRequest '/user/password-reset/email-code' 'POST' `
        @{ email = $identity.Email } $resetHeaders
    Assert-AppCode $resetIssued 200 'Password-reset captcha request'
    $resetCode = Wait-MailCode $identity.Email $resetSubjectTerm
    $reset = Invoke-AppRequest '/user/password-reset' 'POST' @{
        email = $identity.Email; code = $resetCode; password = $identity.NewPassword
        confirmPassword = $identity.NewPassword
    } $resetHeaders
    Assert-AppCode $reset 200 'Password reset'
    $revoked = Invoke-AppRequest '/user/userInfo' 'GET' @{} @{ Authorization = $oldToken }
    Assert-AppCode $revoked 1001 'Old JWT after password reset'
    $newLogin = Invoke-AppRequest '/user/login' 'POST' `
        @{ loginAccount = $identity.Email; password = $identity.NewPassword } $resetHeaders
    Assert-AppCode $newLogin 200 'Login after password reset'

    $accountLimitedBefore = Get-MetricValue 'novel.auth.login' 'outcome:account_limited'
    $accountIp = '198.51.100.243'
    for ($i = 0; $i -lt 5; $i++) {
        $bad = Invoke-AppRequest '/user/login' 'POST' `
            @{ loginAccount = $identity.Email; password = 'definitely-wrong' } `
            @{ 'X-Real-IP' = $accountIp }
        Assert-AppCode $bad 1004 'Account failure accumulation'
    }
    $blocked = Invoke-AppRequest '/user/login' 'POST' `
        @{ loginAccount = $identity.Email; password = $identity.NewPassword } `
        @{ 'X-Real-IP' = $accountIp }
    Assert-AppCode $blocked 1004 'Account failure limit'
    $accountLimitedAfter = Get-MetricValue 'novel.auth.login' 'outcome:account_limited'
    if (($accountLimitedAfter - $accountLimitedBefore) -lt 1) { throw 'Account-limited metric did not grow.' }

    $riskIp = '198.51.100.244'
    for ($i = 0; $i -lt 10; $i++) {
        $bad = Invoke-AppRequest '/user/login' 'POST' `
            @{ loginAccount = "missing-$i@example.test"; password = 'definitely-wrong' } `
            @{ 'X-Real-IP' = $riskIp }
        Assert-AppCode $bad 1004 'IP risk accumulation'
    }
    $captchaRequired = Invoke-AppRequest '/user/login' 'POST' `
        @{ loginAccount = 'another-missing@example.test'; password = 'definitely-wrong' } `
        @{ 'X-Real-IP' = $riskIp }
    Assert-AppCode $captchaRequired 1004 'IP captcha escalation'
    if ($captchaRequired.Json.data.captchaRequired -ne $true) { throw 'IP risk did not require an image captcha.' }

    $metricNames = @((Invoke-RestMethod -Uri "$ManagementUrl/actuator/metrics" -TimeoutSec 5).names)
    foreach ($requiredMetric in @('novel.auth.captcha.request', 'novel.auth.captcha.verify',
        'novel.auth.mail.delivery', 'novel.auth.login', 'novel.auth.password.upgrade',
        'novel.auth.jwt.version', 'novel.auth.argon2.duration')) {
        if ($requiredMetric -notin $metricNames) { throw "Authentication metric is missing: $requiredMetric" }
    }

    Write-Host 'PASS: Argon2 registration, MD5 migration, captcha TTL/single-use/cooldown, login limits, JWT revocation, Mailpit delivery, and metrics are valid.'
}
finally {
    Restore-TrackedContainers { param($container) Restore-LocalContainer $container }
    if ($mailMessageIds.Count -gt 0) {
        try {
            $body = @{ IDs = @($mailMessageIds) } | ConvertTo-Json -Compress
            Invoke-WebRequest -UseBasicParsing -Uri "$MailpitUrl/api/v1/messages" -Method Delete `
                -ContentType 'application/json' -Body $body -TimeoutSec 5 | Out-Null
        } catch { Write-Warning 'Could not delete this run''s Mailpit messages.' }
    }
    if ($createdUsers.Count -gt 0) {
        try {
            Remove-TrackedUsers { param($sql) [void](Invoke-MySql $sql -AllowEmpty) }
            Write-Host 'CLEANED: only this run authentication users and Mailpit messages.'
        } catch { Write-Warning 'Cleanup could not remove all isolated authentication evidence.' }
    }
}
