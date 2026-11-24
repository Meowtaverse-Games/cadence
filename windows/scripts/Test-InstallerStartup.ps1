param(
    [Parameter(Mandatory)][string]$SetupPath,
    [Parameter(Mandatory)][string]$ExpectedVersion
)
$ErrorActionPreference = 'Stop'
$setup = (Resolve-Path $SetupPath).Path
$root = Join-Path $env:RUNNER_TEMP 'Cadence-installer-smoke'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
# Run only on a disposable CI runner. Do not overwrite an existing registration.
if (Get-ItemProperty -Path $runKey -Name Cadence -ErrorAction SilentlyContinue) {
    throw 'Installer smoke test requires no existing Cadence startup registration.'
}
try {
    $process = Start-Process -FilePath $setup -ArgumentList @('--silent', '--installto', "`"$root`"") -PassThru
    if (!$process.WaitForExit(60000)) {
        $process.Kill($true)
        throw 'Silent installation timed out.'
    }
    if ($process.ExitCode -ne 0) { throw "Silent installation failed (exit $($process.ExitCode))." }
    $stub = Join-Path $root 'Cadence.exe'
    if (!(Test-Path -LiteralPath $stub -PathType Leaf)) { throw 'Installed Velopack stub is missing.' }
    if (Test-Path -LiteralPath (Join-Path $root 'Cadence.Tray.exe')) { throw 'Unexpected root executable.' }
    $registered = (Get-ItemProperty -Path $runKey -Name Cadence).Cadence
    if ($registered -ne "`"$stub`"") { throw 'Installer registered an incorrect startup executable.' }
    Write-Host 'Silent installation registered the existing root stub (not the current executable).'
    & "$PSScriptRoot/Test-PackagedStartup.ps1" -Path (Join-Path $root 'current') -ExpectedVersion $ExpectedVersion
} finally {
    Remove-ItemProperty -Path $runKey -Name Cadence -ErrorAction SilentlyContinue
}
