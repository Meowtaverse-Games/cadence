param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
if (!(Test-Path "$Path/Cadence.Tray.exe")) { throw 'Published application is missing.' }
# Only build outputs belong here. Never package local configuration, sessions,
# schedules, logs, test results or credentials (including hidden files).
$unexpected = Get-ChildItem -LiteralPath $Path -Recurse -Force | Where-Object {
    $_.Name -match '^(cadence-firebase\.json.*|GoogleService-Info\.plist|\.firebaserc|session\.dat|timetable\.json|executables\.json|TestResults|logs)$' -or
    (!$_.PSIsContainer -and $_.Extension -notin @('.exe', '.dll', '.pdb', '.xml', '.json')) -or
    (!$_.PSIsContainer -and $_.Extension -eq '.json' -and $_.Name -notmatch '\.(deps|runtimeconfig)\.json$') -or
    $_.Name -match '(?i)(testhost|\.tests?\.|\.trx$|\.pfx$|\.p12$|\.pem$|\.key$)'
}
if ($unexpected) { throw 'Publish folder contains unexpected files. Refusing to package.' }
