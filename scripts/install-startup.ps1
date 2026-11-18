# ログオン時の自動起動を登録・解除する。
param([switch]$Remove)

$taskName = 'RemoteCode'
if ($Remove) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Host "Deleted scheduled task $taskName"
    return
}

$root = Split-Path -Parent $PSScriptRoot
$go = Get-Command go -CommandType Application -ErrorAction Stop | Select-Object -First 1
New-Item -ItemType Directory -Force (Join-Path $root 'data') | Out-Null

$action = New-ScheduledTaskAction -Execute $go.Source -Argument 'run ./cmd/remote-code --no-qr' -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Description 'Remote Code server for controlling Claude / Codex from Android' -Force | Out-Null
Write-Host "Registered scheduled task $taskName to start Remote Code at logon."
Write-Host "To start now: Start-ScheduledTask -TaskName $taskName"
