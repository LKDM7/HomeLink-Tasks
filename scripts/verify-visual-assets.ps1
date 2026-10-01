$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $taskRoot 'src/main/resources/assets/homelink_tasks'
Add-Type -AssemblyName System.Drawing
$textures = @()
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $assets 'textures') -Recurse -Filter '*.png') {
    $bitmap = [Drawing.Bitmap]::FromFile($file.FullName)
    try {
        if ($bitmap.Width -le 0 -or $bitmap.Height -le 0) { throw ('Invalid texture: ' + $file.Name) }
        $textures += @{ file = $file.Name; width = $bitmap.Width; height = $bitmap.Height; sha256 = (Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant() }
    } finally { $bitmap.Dispose() }
}
$models = @()
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $assets 'models') -Recurse -Filter '*.json') {
    $model = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
    foreach ($texture in $model.textures.PSObject.Properties) {
        if ($texture.Value -like 'homelink_tasks:*') {
            $path = Join-Path $assets ('textures/' + $texture.Value.Split(':')[1] + '.png')
            if (!(Test-Path -LiteralPath $path)) { throw ('Missing texture: ' + $path) }
        }
    }
    foreach ($element in $model.elements) {
        foreach ($face in $element.faces.PSObject.Properties) {
            foreach ($uv in $face.Value.uv) { if ($uv -lt 0 -or $uv -gt 16) { throw ('Texture UV outside bounds: ' + $file.Name) } }
            $slot = $face.Value.texture
            if ($slot.StartsWith('#') -and !$model.textures.PSObject.Properties[$slot.Substring(1)]) { throw ('Missing texture slot: ' + $slot) }
        }
    }
    $models += $file.Name
}
$fr = [IO.File]::ReadAllText((Join-Path $assets 'lang/fr_fr.json')) | ConvertFrom-Json
$en = [IO.File]::ReadAllText((Join-Path $assets 'lang/en_us.json')) | ConvertFrom-Json
if (Compare-Object @($fr.PSObject.Properties.Name) @($en.PSObject.Properties.Name)) { throw 'Translation key mismatch' }
foreach ($language in @($fr, $en)) {
    foreach ($entry in $language.PSObject.Properties) {
        if ($entry.Value.Contains([string][char]0xFFFD) -or $entry.Value.Contains([string][char]0x00C3)) { throw ('Broken encoding: ' + $entry.Name) }
    }
}
$report = @{ capturedAt = (Get-Date).ToString('o'); textures = $textures; models = $models; translationKeys = @($fr.PSObject.Properties).Count; valid = $true }
$destination = Join-Path $taskRoot 'build/validation/visual-assets.json'
[IO.File]::WriteAllText($destination, ($report | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output ('Visual assets valid: ' + $textures.Count + ' textures, ' + $models.Count + ' models, ' + $report.translationKeys + ' translation keys per language')
