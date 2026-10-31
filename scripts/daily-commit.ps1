$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
# Only completed Eastern calendar dates, including when a missed task runs late.
$zone = [TimeZoneInfo]::FindSystemTimeZoneById('Eastern Standard Time')
$today = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $zone).ToString('yyyy-MM-dd')
$files = @(Get-ChildItem data/raw -Filter '*.csv' | Where-Object {
    $_.BaseName -match '^(bikes|arrivals)_(\d{4}-\d{2}-\d{2})$' -and $Matches[2] -lt $today
})
if ($files.Count -gt 0) {
    & git add -- ($files.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'git add failed' }
    & git diff --cached --quiet -- ($files.FullName)
    if ($LASTEXITCODE -eq 1) {
        & git commit --only -m "Archive completed collector days" -- ($files.FullName)
        if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
    } elseif ($LASTEXITCODE -ne 0) { throw 'git diff failed' }
}
# Retry a previous unpushed commit even when no new data was written today.
& git push
if ($LASTEXITCODE -ne 0) { throw 'git push failed' }
