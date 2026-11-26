# Keep API retrieval separate from selection so all decisions can be tested offline.
function Get-WindowsReleaseHistory {
    param([Parameter(Mandatory)][scriptblock]$ReadPage)
    $page = 1
    do {
        # Invoke-RestMethod emits a JSON array as one pipeline object. Explicitly
        # enumerate it before counting pages or filtering release properties.
        $batch = @(& $ReadPage $page | ForEach-Object { $_ })
        $batch
        $page++
    } while ($batch.Count -eq 100)
}

function Select-WindowsDeltaBase {
    param([AllowEmptyCollection()][object[]]$Releases = @())
    # Enumerate nested batches too, so callers' array wrapping cannot affect flags.
    $flat = @($Releases | ForEach-Object {
        if ($_ -is [array]) { $_ | ForEach-Object { $_ } } else { $_ }
    })
    $stable = @($flat | Where-Object { !$_.draft -and !$_.prerelease -and $_.tag_name -cmatch '^windows-v' })
    if ($stable.Count -eq 0) { return $null }
    $previous = @($stable | Where-Object { $_.assets.name -contains 'releases.win.json' })
    if ($previous.Count -eq 0) {
        throw 'Stable Windows releases exist, but none contains releases.win.json. Refusing to silently omit delta packages.'
    }
    # The GitHub list is newest first. Velopack downloads the matching channel.
    return $previous[0]
}
