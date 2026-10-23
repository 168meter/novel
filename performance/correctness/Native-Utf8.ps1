function Invoke-CvNativeUtf8 {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Executable,
        [Parameter(Mandatory)][string[]]$Arguments,
        [string]$InputText,
        [string]$InputFile,
        [string]$OutputFile,
        [Parameter(Mandatory)][string]$ErrorFile
    )
    if($InputFile -and $PSBoundParameters.ContainsKey('InputText')){throw 'Specify InputFile or InputText, not both'}
    $encoding=[Text.UTF8Encoding]::new($false,$true)
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$Executable;$start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.StandardInputEncoding=$encoding;$start.StandardOutputEncoding=$encoding;$start.StandardErrorEncoding=$encoding
    foreach($argument in $Arguments){$start.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    $inputStream=$null;$outputStream=$null
    try{
        $null=$process.Start()
        # Drain both output streams while stdin is copied: no pipe-buffer deadlock for large dumps.
        $errorTask=$process.StandardError.ReadToEndAsync()
        if($OutputFile){
            $outputStream=[IO.File]::Open($OutputFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            $outputTask=$process.StandardOutput.BaseStream.CopyToAsync($outputStream)
        }else{$outputTask=$process.StandardOutput.ReadToEndAsync()}
        $inputError=$null
        try{
            if($InputFile){
                $inputStream=[IO.File]::OpenRead($InputFile)
                $null=$inputStream.CopyToAsync($process.StandardInput.BaseStream).GetAwaiter().GetResult()
            }elseif($PSBoundParameters.ContainsKey('InputText')){
                $null=$process.StandardInput.WriteAsync($InputText).GetAwaiter().GetResult()
            }
        }catch{$inputError=$_.Exception.Message}
        finally{try{$process.StandardInput.Close()}catch{if(-not $inputError){$inputError=$_.Exception.Message}}}
        $process.WaitForExit()
        $stdout=$null
        # PowerShell can emit VoidTaskResult from non-generic Task awaiters.
        # The public pipeline must contain only the result object below.
        if($OutputFile){$null=$outputTask.GetAwaiter().GetResult();$outputStream.Flush()}else{$stdout=$outputTask.GetAwaiter().GetResult()}
        $stderr=$errorTask.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($ErrorFile,$stderr,$encoding)
        if($process.ExitCode -eq 0 -and $inputError){throw "Native stdin failed: $inputError"}
        return [pscustomobject]@{ExitCode=$process.ExitCode;Stdout=$stdout;ErrorFile=$ErrorFile;InputError=$inputError}
    }finally{
        if($inputStream){$inputStream.Dispose()}
        if($outputStream){$outputStream.Dispose()}
        $process.Dispose()
    }
}
