param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$ExpectedVersion
)
$ErrorActionPreference = 'Stop'
$executable = (Resolve-Path (Join-Path $Path 'Cadence.Tray.exe')).Path
$stdout = Join-Path $env:RUNNER_TEMP "cadence-smoke-$([guid]::NewGuid()).stdout"
$stderr = Join-Path $env:RUNNER_TEMP "cadence-smoke-$([guid]::NewGuid()).stderr"
try {
    $process = Start-Process -FilePath $executable -ArgumentList @('--cadence-ci-packaged-smoke-test', $ExpectedVersion) -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if (!$process.WaitForExit(60000)) {
        $process.Kill($true)
        throw 'Published application smoke test timed out.'
    }
    # Flush redirected output before reading it.
    $process.WaitForExit()
    Get-Content $stdout
    Get-Content $stderr
    if ($process.ExitCode -ne 0) { throw "Published application smoke test failed (exit $($process.ExitCode))." }
} finally {
    Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue
}
