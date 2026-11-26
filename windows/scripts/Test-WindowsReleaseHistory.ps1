$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/WindowsReleaseHistory.ps1"
Write-Host "Release history tests on PowerShell $($PSVersionTable.PSVersion)"
$script:checks = 0
function Assert-True([bool]$Value, [string]$Name) {
    if (!$Value) { throw "Failed: $Name" }
    $script:checks++
}
function New-Release([string]$Tag, [bool]$Draft = $false, [bool]$Prerelease = $false, [bool]$Manifest = $true) {
    [pscustomobject]@{
        tag_name = $Tag
        draft = $Draft
        prerelease = $Prerelease
        assets = @(if ($Manifest) { [pscustomobject]@{ name = 'releases.win.json' } })
    }
}

Assert-True ($null -eq (Select-WindowsDeltaBase -Releases @())) 'empty history is first release'
foreach ($count in 1..3) {
    $history = @(foreach ($index in 0..($count - 1)) { New-Release "windows-v0.2.$index" })
    Assert-True ($null -ne (Select-WindowsDeltaBase -Releases $history)) "$count flat stable releases"
    Assert-True ($null -ne (Select-WindowsDeltaBase -Releases @(, $history))) "$count wrapped stable releases"

    # Reproduce the old expression without HTTP. Unary comma models the documented
    # Invoke-RestMethod array output (Microsoft Learn, Example 6):
    # https://learn.microsoft.com/powershell/module/microsoft.powershell.utility/invoke-restmethod
    $oldBatch = @(& { , $history })
    Assert-True ($oldBatch.Count -eq 1) 'REST array is wrapped as one pipeline object'
    $oldSelection = @($oldBatch | Where-Object { !$_.draft -and !$_.prerelease -and $_.tag_name -cmatch '^windows-v' -and $_.assets.name -contains 'releases.win.json' })
    Assert-True ($oldSelection.Count -eq $(if ($count -eq 1) { 1 } else { 0 })) "old predicate reproduces failure with $count releases"
    $retrieved = @(Get-WindowsReleaseHistory -ReadPage { param($page) , $history })
    Assert-True ($retrieved.Count -eq $count) "retrieval enumerates $count releases"
    Assert-True ($null -ne (Select-WindowsDeltaBase -Releases $retrieved)) "retrieved $count releases select delta base"
}

$excluded = @(
    (New-Release 'windows-v0.3.0' -Draft $true),
    (New-Release 'windows-v0.3.0-preview' -Prerelease $true),
    (New-Release 'ios-v0.3.0'),
    (New-Release 'android-v0.3.0')
)
Assert-True ($null -eq (Select-WindowsDeltaBase -Releases $excluded)) 'only excluded releases is first Windows release'
$eligible = New-Release 'windows-v0.2.1'
$mixed = $excluded + @((New-Release 'windows-v0.2.2' -Manifest $false), $eligible, (New-Release 'windows-v0.2.0'))
Assert-True ((Select-WindowsDeltaBase -Releases $mixed).tag_name -eq $eligible.tag_name) 'mixed history selects first eligible base'
foreach ($history in @(
    @(, (New-Release 'windows-v0.2.0' -Manifest $false)),
    @((New-Release 'windows-v0.2.0' -Manifest $false), (New-Release 'windows-v0.2.1' -Manifest $false))
)) {
    $threw = $false
    try { Select-WindowsDeltaBase -Releases $history | Out-Null } catch { $threw = $true }
    Assert-True $threw 'stable history without manifest must fail'
}

# The page count must count releases, not the single wrapped REST response.
$script:pages = [Collections.Generic.List[int]]::new()
$history = @(Get-WindowsReleaseHistory -ReadPage {
    param($page)
    $script:pages.Add($page)
    if ($page -eq 1) { , @(foreach ($index in 1..100) { New-Release "windows-v0.1.$index" }) }
    elseif ($page -eq 2) { , @((New-Release 'windows-v0.0.1'), (New-Release 'windows-v0.0.0')) }
    else { throw 'Unexpected extra page' }
})
Assert-True ($history.Count -eq 102) 'pagination retrieves all 102 releases'
Assert-True (($script:pages -join ',') -eq '1,2') 'pagination stops after partial page'
Assert-True ($null -ne (Select-WindowsDeltaBase -Releases $history)) 'paginated history selects delta base'
$threw = $false
try { Get-WindowsReleaseHistory -ReadPage { throw 'synthetic API failure' } | Out-Null } catch { $threw = $true }
Assert-True $threw 'API errors propagate'
Write-Host "Passed $script:checks release history assertions (no network)."
