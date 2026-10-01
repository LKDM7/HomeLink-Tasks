param([string]$Label = 'in-game-final')
$ErrorActionPreference = 'Stop'
if ($Label -notmatch '^[a-z0-9-]+$') { throw 'Invalid evidence label' }
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
if (!$env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/ms-21.0.11' }
$session = Get-Date -Format 'yyyyMMdd-HHmmss'
& .\gradlew.bat build --offline -PwithStorage
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$destination = Join-Path $taskRoot ('build/validation/evidence/' + $Label)
[IO.Directory]::CreateDirectory($destination) | Out-Null
$processes = @()
# Do not run other Gradle profiles concurrently: switching verification source sets
# removes optional integration classes from the shared output directory.
foreach ($role in @('Host','Guest')) {
    $task = if ($role -eq 'Host') { 'runInGame' } else { 'runInGameGuest' }
    $gradle = (Join-Path $taskRoot 'gradlew.bat').Replace("'", "''")
    $command = "& '$gradle' $task --offline -PwithStorage -PvalidationSession=$session; exit `$LASTEXITCODE"
    $processes += Start-Process -FilePath 'powershell.exe' -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru `
        -ArgumentList @('-NoProfile','-Command',$command) `
        -RedirectStandardOutput (Join-Path $destination ($role + '-gradle.log')) `
        -RedirectStandardError (Join-Path $destination ($role + '-gradle-error.log'))
    $null = $processes[-1].Handle
}
$deadline = (Get-Date).AddMinutes(20)
while (@($processes | Where-Object { !$_.HasExited }).Count -gt 0) {
    if ((Get-Date) -gt $deadline) { throw 'Client validation exceeded 20 minutes; inspect running clients' }
    Start-Sleep -Seconds 1
    foreach ($process in $processes) { $process.Refresh() }
    if (@($processes | Where-Object { $_.HasExited -and $_.ExitCode -ne 0 }).Count -gt 0) {
        $exchange = Join-Path $taskRoot ('build/validation/client-exchange/' + $session)
        [IO.Directory]::CreateDirectory($exchange) | Out-Null
        [IO.File]::WriteAllText((Join-Path $exchange 'abort.txt'), 'Other client failed')
    }
}
foreach ($process in $processes) { if ($process.ExitCode -ne 0) { throw ('Client process failed: ' + $process.ExitCode) } }
$hostLog = [IO.File]::ReadAllText((Join-Path $taskRoot 'build/validation/in-game/logs/latest.log'))
$guestLog = [IO.File]::ReadAllText((Join-Path $taskRoot 'build/validation/in-game-guest/logs/latest.log'))
foreach ($marker in @('TASKS_IN_GAME_OK','TASKS_UX_ITEM_SELECTION_OK','TASKS_REAL_CHUNK_UNLOAD_RELOAD_OK','TASKS_TWO_CLIENTS_CONFLICT_PRIVATE_HUD_OK','TASKS_TWO_CLIENTS_REVOCATION_OK')) {
    if (!$hostLog.Contains($marker)) { throw ('Missing marker: ' + $marker) }
}
if (!$guestLog.Contains('TASKS_GUEST_OK') -or ($hostLog + $guestLog).Contains('TASKS_IN_GAME_FAILED')) { throw 'Client checks failed' }
Copy-Item -LiteralPath 'build/validation/in-game/logs/latest.log' -Destination (Join-Path $destination 'host.log') -Force
Copy-Item -LiteralPath 'build/validation/in-game-guest/logs/latest.log' -Destination (Join-Path $destination 'guest.log') -Force
foreach ($role in @('host','guest')) {
    $directory = if ($role -eq 'host') { 'in-game' } else { 'in-game-guest' }
    $captureDir = Join-Path $destination $role
    [IO.Directory]::CreateDirectory($captureDir) | Out-Null
    # Only copy screenshots listed in this execution's log, never stale captures.
    $log = if ($role -eq 'host') { $hostLog } else { $guestLog }
    foreach ($match in [regex]::Matches($log, 'TASKS_CAPTURE ([a-z0-9_-]+) gui=')) {
        $name = $match.Groups[1].Value + '.png'
        Copy-Item -LiteralPath (Join-Path $taskRoot ('build/validation/' + $directory + '/screenshots/' + $name)) -Destination $captureDir -Force
    }
}
$files = @(Get-ChildItem -LiteralPath $destination -Recurse -File | Where-Object Extension -eq '.png' | ForEach-Object {
    @{ file = $_.FullName.Substring($destination.Length + 1); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$dependencies = @()
foreach ($entry in @(@('HomeCore','homecore-1.12.0.jar'), @('HomeLink Storage','homelink_storage-1.1.1.jar'), @('HomeLinkEnergy','homelink_energy-0.4.0.jar'))) {
    $path = Join-Path (Split-Path -Parent $taskRoot) ($entry[0] + '/build/libs/' + $entry[1])
    $dependencies += @{ file = $entry[1]; sha256 = (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() }
}
$receipt = @{ label = $Label; capturedAt = (Get-Date).ToString('o'); session = $session; realClients = 2; dependencies = $dependencies;
    jarSha256 = (Get-FileHash -LiteralPath 'build/libs/homelink_tasks-0.1.0.jar' -Algorithm SHA256).Hash.ToLowerInvariant();
    hostLogSha256 = (Get-FileHash -LiteralPath (Join-Path $destination 'host.log')).Hash.ToLowerInvariant();
    guestLogSha256 = (Get-FileHash -LiteralPath (Join-Path $destination 'guest.log')).Hash.ToLowerInvariant();
    screenshots = $files; music = 'disabled'; chunkUnloadReload = $true; itemSelectionViaUi = $true }
[IO.File]::WriteAllText((Join-Path $destination 'receipt.json'), ($receipt | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output ('In-game evidence saved: ' + $destination)
