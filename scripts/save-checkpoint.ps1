param([ValidateSet('pause','validation')][string]$Kind = 'pause')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$checkpointRoot = Join-Path $taskRoot 'checkpoints'
New-Item -ItemType Directory -Force $checkpointRoot | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$archivePath = Join-Path $checkpointRoot ('homelink-tasks-' + $Kind + '-' + $stamp + '.zip')
$files = [Collections.Generic.List[string]]::new()
foreach ($directory in @('src', 'gradle', 'scripts', 'integration', 'docs', '.github')) {
    $path = Join-Path $taskRoot $directory
    if (Test-Path -LiteralPath $path) {
        foreach ($file in Get-ChildItem -LiteralPath $path -Recurse -File) { $files.Add($file.FullName) }
    }
}
foreach ($name in @('README.md', 'REPRISE.md', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', '.gitignore')) {
    $path = Join-Path $taskRoot $name
    if (Test-Path -LiteralPath $path) { $files.Add($path) }
}
foreach ($directory in @('build/test-results/test', 'build/libs', 'build/validation/evidence', 'build/delivery')) {
    $path = Join-Path $taskRoot $directory
    if (Test-Path -LiteralPath $path) {
        foreach ($file in Get-ChildItem -LiteralPath $path -Recurse -File) { $files.Add($file.FullName) }
    }
}
foreach ($log in @('build/validation/gametest/logs/latest.log', 'build/validation/persistence/logs/latest.log',
    'build/validation/benchmark.json', 'build/validation/release-receipt.json')) {
    $path = Join-Path $taskRoot $log
    if (Test-Path -LiteralPath $path) { $files.Add($path) }
}
$archive = [IO.Compression.ZipFile]::Open($archivePath, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        $relative = $file.Substring($taskRoot.Length + 1).Replace('\', '/')
        $source = [IO.File]::Open($file, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            $entry = $archive.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
            $destination = $entry.Open()
            try { $source.CopyTo($destination) } finally { $destination.Dispose() }
        } finally { $source.Dispose() }
    }
} finally { $archive.Dispose() }
$check = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    if ($check.GetEntry('REPRISE.md') -eq $null) { throw 'Missing resume instructions in archive' }
    if ($check.Entries.Count -ne $files.Count) { throw 'Incomplete archive' }
} finally { $check.Dispose() }
$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
[IO.File]::WriteAllText($archivePath + '.sha256', $hash + '  ' + [IO.Path]::GetFileName($archivePath) + "`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $checkpointRoot '.gitignore'), "*`n!.gitignore`n", [Text.UTF8Encoding]::new($false))
Write-Output ('Checkpoint: ' + $archivePath)
Write-Output ('Files: ' + $files.Count)
Write-Output ('SHA256: ' + $hash)
