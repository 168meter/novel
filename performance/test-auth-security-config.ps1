$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$failures = [System.Collections.Generic.List[object]]::new()

function Add-Failure([string]$Path, [string]$Rule) {
    $failures.Add([pscustomobject]@{ Path = $Path; Rule = $Rule })
}

function Read-YamlScalars([string]$Path) {
    $stack = [System.Collections.Generic.List[object]]::new()
    $scalars = [System.Collections.Generic.List[object]]::new()
    $lineNumber = 0

    foreach ($line in Get-Content -LiteralPath $Path) {
        $lineNumber++
        if ($line -match "`t") {
            throw "YAML tabs are not supported in $Path at line $lineNumber."
        }
        if ($line -match '^\s*(?:#.*)?$') { continue }
        if ($line -match '^\s*---(?:\s+#.*)?$') {
            $stack.Clear()
            continue
        }
        if ($line -notmatch '^(?<indent> *)(?<key>[^:#][^:]*):(?<tail>.*)$') {
            continue
        }

        $indent = $matches.indent.Length
        while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].Indent -ge $indent) {
            $stack.RemoveAt($stack.Count - 1)
        }

        $key = $matches.key.Trim()
        $tail = $matches.tail.Trim()
        $pathSegments = @($stack | ForEach-Object Key) + $key
        $yamlPath = $pathSegments -join '.'
        if (-not $tail -or $tail.StartsWith('#')) {
            $stack.Add([pscustomobject]@{ Indent = $indent; Key = $key })
            continue
        }

        $value = $tail
        if (($value.StartsWith("'") -and $value.EndsWith("'")) -or
            ($value.StartsWith('"') -and $value.EndsWith('"'))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $scalars.Add([pscustomobject]@{
            Path = $yamlPath
            Value = $value
            Line = $lineNumber
        })
    }

    return $scalars
}

function Get-ScalarValue([object[]]$Scalars, [string]$YamlPath) {
    $matchingScalars = @($Scalars | Where-Object Path -eq $YamlPath)
    if ($matchingScalars.Count -ne 1) { return $null }
    return $matchingScalars[0].Value
}

$applicationPath = Join-Path $root 'novel-front\src\main\resources\application.yml'
$configurationDirectories = @(
    (Join-Path $root 'novel-front\src\main\resources')
    (Join-Path $root 'novel-front\src\main\build\config')
)
$profilePaths = @(
    $configurationDirectories |
        ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter 'application*.yml' -File } |
        Select-Object -ExpandProperty FullName -Unique
)

$parsedByFile = @{}
foreach ($path in $profilePaths) {
    try {
        $parsedByFile[$path] = @(Read-YamlScalars $path)
    }
    catch {
        Add-Failure $path 'valid-yaml-structure'
    }
}

if ($parsedByFile.ContainsKey($applicationPath)) {
    $application = $parsedByFile[$applicationPath]
    $contracts = [ordered]@{
        'jwt.secret' = '${JWT_SECRET}'
        'cache.manager.password' = '${CACHE_MANAGER_PASSWORD}'
        'spring.ai.openai.api-key' = '${NOVEL_AI_API_KEY:}'
        'spring.ai.openai.image.api-key' = '${NOVEL_AI_API_KEY:}'
        'novel.auth.hmac-secret' = '${NOVEL_AUTH_HMAC_SECRET}'
    }
    foreach ($entry in $contracts.GetEnumerator()) {
        if ((Get-ScalarValue $application $entry.Key) -cne $entry.Value) {
            Add-Failure $applicationPath "environment-placeholder:$($entry.Key)"
        }
    }
}

foreach ($path in $profilePaths) {
    if (-not $parsedByFile.ContainsKey($path)) { continue }
    foreach ($scalar in $parsedByFile[$path]) {
        $isEnvironmentReference = $scalar.Value -match '^\$\{[A-Z][A-Z0-9_]*(?::[^}]*)?\}$'
        if ($scalar.Path -match '(^|\.)api-key$' -and $scalar.Value -and -not $isEnvironmentReference) {
            Add-Failure $path 'plaintext-api-key'
        }
        if ($scalar.Path -eq 'spring.mail.password' -and $scalar.Value -and -not $isEnvironmentReference) {
            Add-Failure $path 'plaintext-smtp-password'
        }
        if ($scalar.Path -eq 'cache.manager.password' -and $scalar.Value -cne '${CACHE_MANAGER_PASSWORD}') {
            Add-Failure $path 'fixed-cache-manager-password'
        }
        if ($scalar.Path -eq 'jwt.secret' -and $scalar.Value -cne '${JWT_SECRET}') {
            Add-Failure $path 'fixed-jwt-secret'
        }
        if ($scalar.Path -eq 'novel.auth.hmac-secret' -and $scalar.Value -cne '${NOVEL_AUTH_HMAC_SECRET}') {
            Add-Failure $path 'fixed-auth-hmac-secret'
        }
    }
}

$launcher = Join-Path $root 'performance\start-front-monitoring.ps1'
$hostExecutable = (Get-Process -Id $PID).Path
$requiredVariables = @('JWT_SECRET', 'CACHE_MANAGER_PASSWORD', 'NOVEL_AUTH_HMAC_SECRET')
foreach ($missingVariable in $requiredVariables) {
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $process.StartInfo.FileName = $hostExecutable
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.Arguments = '-NoProfile -NonInteractive -File "{0}" -MavenCommand __auth_config_test_missing_maven__' -f $launcher.Replace('"', '\"')

    foreach ($name in $requiredVariables) {
        if ($name -eq $missingVariable) {
            [void]$process.StartInfo.EnvironmentVariables.Remove($name)
        }
        else {
            $process.StartInfo.EnvironmentVariables[$name] = "test-sentinel-$name"
        }
    }

    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $combinedOutput = $stdout + $stderr

    if ($process.ExitCode -eq 0 -or $combinedOutput -notmatch [regex]::Escape($missingVariable)) {
        Add-Failure $launcher "missing-environment-variable:$missingVariable"
    }
    if ($combinedOutput -match 'test-sentinel-') {
        Add-Failure $launcher 'environment-value-disclosure'
    }
    $process.Dispose()
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Error ("{0} :: {1}" -f $failure.Path, $failure.Rule) -ErrorAction Continue
    }
    exit 1
}

Write-Host 'PASS: authentication configuration uses environment boundaries and the launcher fails closed.'
