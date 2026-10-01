param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$Label,
      [ValidateSet('gametest','persistence')][string]$Profile = 'gametest')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $taskRoot ('build/validation/' + $Profile + '/logs/latest.log')
$destination = Join-Path $taskRoot ('build/validation/evidence/' + $Label)
[IO.Directory]::CreateDirectory($destination) | Out-Null
$log = [IO.File]::ReadAllText($source)
if ($log -notmatch 'All (\d+) required tests passed') { throw 'No successful GameTest completion in log' }
$count = [int]$Matches[1]
Copy-Item -LiteralPath $source -Destination (Join-Path $destination 'server.log')
if (Test-Path -LiteralPath (Join-Path $taskRoot 'build/validation/benchmark.json')) {
    Copy-Item -LiteralPath (Join-Path $taskRoot 'build/validation/benchmark.json') -Destination (Join-Path $destination 'benchmark.json')
}
$unitRoot = Join-Path $taskRoot 'build/test-results/test'
$units = 0
foreach ($file in Get-ChildItem -LiteralPath $unitRoot -Filter 'TEST-*.xml' -File) {
    [xml]$report = [IO.File]::ReadAllText($file.FullName)
    if ([int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0) { throw 'Unit test failures' }
    $units += [int]$report.testsuite.tests
    Copy-Item -LiteralPath $file.FullName -Destination $destination
}
$jar = Join-Path $taskRoot 'build/libs/homelink_tasks-0.1.0.jar'
$receipt = @{ label = $Label; profile = $Profile; capturedAt = (Get-Date).ToString('o'); unitTests = $units;
    gameTests = $count; jarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant();
    serverLogSha256 = (Get-FileHash -LiteralPath (Join-Path $destination 'server.log') -Algorithm SHA256).Hash.ToLowerInvariant();
    persistenceRead = $log.Contains('TASKS_PERSISTENCE_READ_OK') }
[IO.File]::WriteAllText((Join-Path $destination 'receipt.json'), ($receipt | ConvertTo-Json) + "`n", [Text.UTF8Encoding]::new($false))
Write-Output ('Evidence saved: ' + $Label + ' / ' + $units + ' unit tests / ' + $count + ' GameTests')
