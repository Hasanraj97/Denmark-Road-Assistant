<#
    Puts back every file the review update replaced (from its *.before-review copy)
    and moves the files the update added into thesis-evidence\review-undone.
#>
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent $Here
$manifest = Get-Content (Join-Path $Here 'review-update\manifest.json') -Raw | ConvertFrom-Json
$undone = Join-Path $Here 'review-undone'
$restored = 0; $moved = 0
foreach ($f in $manifest) {
    $dest = Join-Path $Root ($f.path -replace '/', '\')
    $backup = "$dest.before-review"
    if (Test-Path $backup) {
        Copy-Item $backup $dest -Force
        Remove-Item $backup -Force
        $restored++
    } elseif ($f.isNew -and (Test-Path $dest)) {
        $target = Join-Path $undone ($f.path -replace '/', '\')
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Move-Item $dest $target -Force
        $moved++
    }
}
Write-Host "$restored files restored, $moved added files moved to $undone"
