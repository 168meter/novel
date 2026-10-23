function Get-ModerationFreshReports {
 param([Parameter(Mandatory)][string]$Directory,[Parameter(Mandatory)][DateTime]$StartedUtc,[Parameter(Mandatory)][string[]]$Names)
 foreach($report in Get-ChildItem -LiteralPath $Directory -File -ErrorAction SilentlyContinue) {
  if($report.LastWriteTimeUtc -lt $StartedUtc){continue}
  foreach($name in $Names){if($report.Name -like "TEST-*.$name.xml"){$report;break}}
 }
}
