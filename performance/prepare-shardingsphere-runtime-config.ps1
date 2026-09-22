param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,
    [Parameter(Mandatory = $true)]
    [string]$DestinationPath,
    [ValidateRange(250, 60000)]
    [int]$ConnectionTimeoutMs = 5000,
    [ValidateRange(250, 60000)]
    [int]$ValidationTimeoutMs = 3000
)

$ErrorActionPreference = 'Stop'

if ($ValidationTimeoutMs -ge $ConnectionTimeoutMs) {
    throw 'ValidationTimeoutMs must be lower than ConnectionTimeoutMs.'
}
if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
    throw "ShardingSphere source configuration was not found: $SourcePath"
}

$sourceFullPath = [IO.Path]::GetFullPath($SourcePath)
$destinationFullPath = [IO.Path]::GetFullPath($DestinationPath)
if ([string]::Equals($sourceFullPath, $destinationFullPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The generated ShardingSphere configuration must not overwrite its source.'
}

$content = [IO.File]::ReadAllText($sourceFullPath)
$lines = [System.Collections.Generic.List[string]]::new()
foreach ($line in [regex]::Split($content, '\r?\n')) {
    $lines.Add($line)
}

$hikariIndexes = [System.Collections.Generic.List[int]]::new()
for ($index = 0; $index -lt $lines.Count; $index++) {
    if ($lines[$index] -match '^(\s*)dataSourceClassName:\s*com\.zaxxer\.hikari\.HikariDataSource\s*(?:#.*)?$') {
        $hikariIndexes.Add($index)
    }
}
if ($hikariIndexes.Count -eq 0) {
    throw 'No Hikari datasource was found in the ShardingSphere configuration.'
}

for ($hikariPosition = $hikariIndexes.Count - 1; $hikariPosition -ge 0; $hikariPosition--) {
    $hikariIndex = $hikariIndexes[$hikariPosition]
    [void]($lines[$hikariIndex] -match '^(\s*)')
    $propertyIndent = $Matches[1]
    $propertyIndentLength = $propertyIndent.Length
    $blockEnd = $lines.Count
    for ($index = $hikariIndex + 1; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        [void]($line -match '^(\s*)')
        if ($Matches[1].Length -lt $propertyIndentLength) {
            $blockEnd = $index
            break
        }
    }

    foreach ($property in @(
        @{ Name = 'validationTimeout'; Value = $ValidationTimeoutMs },
        @{ Name = 'connectionTimeout'; Value = $ConnectionTimeoutMs }
    )) {
        $propertyIndexes = [System.Collections.Generic.List[int]]::new()
        for ($index = $hikariIndex + 1; $index -lt $blockEnd; $index++) {
            if ($lines[$index] -match ('^' + [regex]::Escape($propertyIndent) + [regex]::Escape($property.Name) + '\s*:')) {
                $propertyIndexes.Add($index)
            }
        }

        if ($propertyIndexes.Count -eq 0) {
            $lines.Insert($hikariIndex + 1, "$propertyIndent$($property.Name): $($property.Value)")
            $blockEnd++
        }
        else {
            $lines[$propertyIndexes[0]] = "$propertyIndent$($property.Name): $($property.Value)"
            for ($duplicate = $propertyIndexes.Count - 1; $duplicate -ge 1; $duplicate--) {
                $lines.RemoveAt($propertyIndexes[$duplicate])
                $blockEnd--
            }
        }
    }
}

$destinationDirectory = Split-Path -Parent $destinationFullPath
if (-not (Test-Path -LiteralPath $destinationDirectory -PathType Container)) {
    [void](New-Item -ItemType Directory -Path $destinationDirectory)
}
if ($env:OS -eq 'Windows_NT') {
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $directorySecurity = [Security.AccessControl.DirectorySecurity]::new()
    $directorySecurity.SetAccessRuleProtection($true, $false)
    $directorySecurity.SetOwner($currentIdentity.User)
    $currentUserRule = [Security.AccessControl.FileSystemAccessRule]::new(
        $currentIdentity.User,
        [Security.AccessControl.FileSystemRights]::FullControl,
        ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [Security.AccessControl.InheritanceFlags]::ObjectInherit),
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
    [void]$directorySecurity.AddAccessRule($currentUserRule)
    Set-Acl -LiteralPath $destinationDirectory -AclObject $directorySecurity
}
else {
    & chmod 700 -- $destinationDirectory
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not restrict the generated ShardingSphere configuration directory.'
    }
}
if (Test-Path -LiteralPath $destinationFullPath) {
    Remove-Item -LiteralPath $destinationFullPath -Force
}
$utf8WithoutBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($destinationFullPath, ($lines -join [Environment]::NewLine), $utf8WithoutBom)
if ($env:OS -ne 'Windows_NT') {
    & chmod 600 -- $destinationFullPath
    if ($LASTEXITCODE -ne 0) {
        Remove-Item -LiteralPath $destinationFullPath -Force -ErrorAction SilentlyContinue
        throw 'Could not restrict the generated ShardingSphere configuration file.'
    }
}
