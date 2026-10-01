$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$assetRoot = Join-Path $taskRoot 'src/main/resources/assets/homelink_tasks'
$dataRoot = Join-Path $taskRoot 'src/main/resources/data/homelink_tasks'
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Json($path, $value) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 30) + "`n", $utf8)
}
function Element($from, $to, $texture) {
    $faces = [ordered]@{}
    foreach ($face in @('north','south','east','west','up','down')) {
        $faces[$face] = @{ texture = $texture; uv = @(0,0,16,16) }
    }
    return @{ from = $from; to = $to; faces = $faces }
}

Add-Type -AssemblyName System.Drawing
foreach ($material in @('frame','copper','glass','mount','steel')) {
    $bitmap = [Drawing.Bitmap]::new(32,32)
    for ($y = 0; $y -lt 32; $y++) {
        for ($x = 0; $x -lt 32; $x++) {
            $grain = (($x * 7 + $y * 13) % 5) - 2
            $rgb = switch ($material) {
                'frame' { @((66 + $grain),(69 + $grain),(71 + $grain)) }
                'steel' { @((149 + $grain),(152 + $grain),(151 + $grain)) }
                'copper' { @((188 + $grain),(143 + $grain),(93 + $grain)) }
                'mount' { @((35 + $grain),(38 + $grain),(40 + $grain)) }
                'glass' { @((22 + $grain),(27 + $grain),(29 + $grain)) }
            }
            if ($material -eq 'glass') {
                if ($y -eq 3 -and $x -ge 3 -and $x -le 28) { $rgb = @(188,143,93) }
                if ($x -ge 4 -and $x -le 6 -and ($y -eq 10 -or $y -eq 17 -or $y -eq 24)) { $rgb = @(175,177,173) }
                if ($x -ge 10 -and $x -le 26 -and ($y -eq 10 -or $y -eq 17 -or $y -eq 24)) { $rgb = @(75,80,81) }
            }
            $bitmap.SetPixel($x,$y,[Drawing.Color]::FromArgb(255,$rgb[0],$rgb[1],$rgb[2]))
        }
    }
    $texturePath = Join-Path $assetRoot ('textures/block/display_' + $material + '.png')
    $bitmap.Save($texturePath,[Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

foreach ($variant in @(@('task_display',14,12),@('task_display_medium',30,20),@('task_display_large',46,30))) {
    $name = $variant[0]; $wide = $variant[1]; $high = $variant[2]
    $left = 8 - $wide / 2; $right = 8 + $wide / 2
    $bottom = 8 - $high / 2; $top = 8 + $high / 2
    $elements = @(
        (Element @(3,3,15.5) @(13,13,16) '#mount'),
        (Element @(($left+0.5),($bottom+0.5),13.5) @(($right-0.5),($top-0.5),15.5) '#mount'),
        (Element @($left,$bottom,13) @($right,($bottom+1.5),15.5) '#frame'),
        (Element @($left,($top-1.5),13) @($right,$top,15.5) '#frame'),
        (Element @($left,($bottom+1.5),13) @(($left+1.5),($top-1.5),15.5) '#frame'),
        (Element @(($right-1.5),($bottom+1.5),13) @($right,($top-1.5),15.5) '#frame'),
        (Element @(($left+1.5),($bottom+1.5),13.15) @(($right-1.5),($top-1.5),13.5) '#glass'),
        (Element @(($left+2),($bottom+0.4),12.95) @(($right-2),($bottom+0.8),13.05) '#copper')
    )
    foreach ($sx in @(($left+0.5),($right-0.9))) {
        foreach ($sy in @(($bottom+0.5),($top-0.9))) {
            $elements += (Element @($sx,$sy,12.94) @(($sx+0.4),($sy+0.4),13.05) '#steel')
        }
    }
    Write-Json (Join-Path $assetRoot ('models/block/' + $name + '.json')) @{
        parent = 'minecraft:block/block'; textures = @{
            frame = 'homelink_tasks:block/display_frame'; mount = 'homelink_tasks:block/display_mount'
            copper = 'homelink_tasks:block/display_copper'; glass = 'homelink_tasks:block/display_glass'
            steel = 'homelink_tasks:block/display_steel'; particle = 'homelink_tasks:block/display_frame'
        }; elements = $elements
    }
    $scale = [Math]::Round(11.0 / $wide, 4)
    Write-Json (Join-Path $assetRoot ('models/item/' + $name + '.json')) @{
        parent = ('homelink_tasks:block/' + $name)
        display = @{
            gui = @{ rotation = @(12,200,0); translation = @(0,0,0); scale = @($scale,$scale,$scale) }
            ground = @{ rotation = @(0,0,0); translation = @(0,2,0); scale = @($scale,$scale,$scale) }
            fixed = @{ rotation = @(0,180,0); translation = @(0,0,-4); scale = @($scale,$scale,$scale) }
            firstperson_righthand = @{ rotation = @(0,165,0); translation = @(0,2,-1); scale = @($scale,$scale,$scale) }
            thirdperson_righthand = @{ rotation = @(75,135,0); translation = @(0,3,1); scale = @($scale,$scale,$scale) }
        }
    }
    $states = [ordered]@{}
    foreach ($entry in @(@('north',0),@('east',90),@('south',180),@('west',270))) {
        $states['facing=' + $entry[0]] = @{ model = ('homelink_tasks:block/' + $name); y = $entry[1] }
    }
    Write-Json (Join-Path $assetRoot ('blockstates/' + $name + '.json')) @{ variants = $states }
    Write-Json (Join-Path $dataRoot ('loot_table/blocks/' + $name + '.json')) @{
        type = 'minecraft:block'; pools = @(@{ rolls = 1; entries = @(@{ type = 'minecraft:item'; name = ('homelink_tasks:' + $name) });
            conditions = @(@{ condition = 'minecraft:survives_explosion' }) })
    }
    if ($name -ne 'task_display') {
        $baseDisplay = if ($name -eq 'task_display_medium') { 'task_display' } else { 'task_display_medium' }
        Write-Json (Join-Path $dataRoot ('recipe/' + $name + '.json')) @{
            type = 'minecraft:crafting_shaped'; category = 'misc'; pattern = @('IGI','IDI','IRI')
            key = @{ I = @{ item = 'minecraft:iron_ingot' }; G = @{ item = 'minecraft:glass' };
                D = @{ item = ('homelink_tasks:' + $baseDisplay) }; R = @{ item = 'minecraft:redstone' } }
            result = @{ id = ('homelink_tasks:' + $name); count = 1 }
        }
    }
}
Write-Json (Join-Path $taskRoot 'src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json') @{
    replace = $false; values = @('homelink_tasks:task_display','homelink_tasks:task_display_medium','homelink_tasks:task_display_large')
}
Write-Output 'Three display models, item models, blockstates, loot tables and textures generated.'
