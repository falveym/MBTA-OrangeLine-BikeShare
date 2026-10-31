param([switch]$IncludeDailyCommit)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$launcher = Join-Path $repo 'run-collectors.bat'
$jar = Join-Path $repo 'target/mbta-orangeline-bikeshare-1.0-SNAPSHOT-jar-with-dependencies.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build with mvn package first.' }
if (!(Get-Command java -ErrorAction SilentlyContinue) -and !$env:JAVA_HOME) {
    throw 'Configure Java on PATH or set JAVA_HOME before installing tasks.'
}
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable
$names = @('MBTA-Bluebikes-arrivals', 'MBTA-Bluebikes-bikes')
if ($IncludeDailyCommit) {
    if ((Get-TimeZone).Id -ne 'Eastern Standard Time') { throw 'Daily commit scheduling expects the Windows time zone to be Eastern.' }
    $names += 'MBTA-Bluebikes-daily-commit'
}
foreach ($name in $names) {
    if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) {
        throw "Task $name already exists. Review it before replacing it."
    }
}
foreach ($collector in @('arrivals', 'bikes')) {
    $name = "MBTA-Bluebikes-$collector"
    $action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument ('/c ""{0}" {1}"' -f $launcher, $collector) -WorkingDirectory $repo
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
    Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
}
if ($IncludeDailyCommit) {
    $name = 'MBTA-Bluebikes-daily-commit'
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -File "{0}"' -f (Join-Path $PSScriptRoot 'daily-commit.ps1')) -WorkingDirectory $repo
    $trigger = New-ScheduledTaskTrigger -Daily -At '02:00'
    Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
}
Write-Host 'Tasks registered for the next logon. Use Task Scheduler to start them now or configure unattended startup.'
