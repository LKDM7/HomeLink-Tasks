param([Parameter(Mandatory=$true)][string]$DependencyRoot)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$encoding = [Text.UTF8Encoding]::new($false)
$taskJar = Join-Path $taskRoot 'build/libs/homelink_tasks-0.1.0.jar'
$taskHash = (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash.ToLowerInvariant()
$checks = @()
foreach ($label in @('with-storage-final','without-storage-final','persistence-write-final','persistence-read-final')) {
    $evidence = Join-Path $taskRoot ('build/validation/evidence/' + $label)
    $receipt = [IO.File]::ReadAllText((Join-Path $evidence 'receipt.json')) | ConvertFrom-Json
    if ($receipt.jarSha256 -ne $taskHash) { throw ('Different Tasks JAR in ' + $label) }
    if ($receipt.unitTests -lt 75) { throw 'Final unit test result missing' }
    if ($label -eq 'with-storage-final' -and $receipt.gameTests -ne 45) { throw 'Storage runtime check missing' }
    if ($label -ne 'with-storage-final' -and $receipt.gameTests -ne 39) { throw 'Runtime check missing' }
    if ($label -eq 'persistence-read-final' -and !$receipt.persistenceRead) { throw 'Persistence read marker missing' }
    $checks += $receipt
}
$clientEvidence = Join-Path $taskRoot 'build/validation/evidence/in-game-final'
$clientReceipt = [IO.File]::ReadAllText((Join-Path $clientEvidence 'receipt.json')) | ConvertFrom-Json
if ($clientReceipt.jarSha256 -ne $taskHash -or $clientReceipt.realClients -ne 2 -or !$clientReceipt.chunkUnloadReload) {
    throw 'Final in-game evidence does not match the current release'
}
if (@($clientReceipt.screenshots).Count -lt 110 -or !$clientReceipt.itemSelectionViaUi) { throw 'Incomplete UX validation campaign' }
foreach ($capture in $clientReceipt.screenshots) {
    if ((Get-FileHash -LiteralPath (Join-Path $clientEvidence $capture.file)).Hash.ToLowerInvariant() -ne $capture.sha256) {
        throw ('Changed screenshot: ' + $capture.file)
    }
}
$gallery = [Text.StringBuilder]::new()
$null = $gallery.Append('<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>HomeLink Tasks - validation en jeu</title><style>body{font:16px system-ui;background:#252729;color:#eee;margin:24px}a{color:#d2b181}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:20px}figure{margin:0}img{width:100%;height:auto}figcaption{padding:8px 0;font-size:14px}</style><h1>HomeLink Tasks - validation en jeu</h1><p>Captures des deux clients Minecraft. Cliquez pour ouvrir une image à sa taille réelle.</p><p><a href="host.log">Journal principal</a> · <a href="guest.log">Second client</a> · <a href="receipt.json">Empreintes et résultat</a></p><main>')
foreach ($capture in $clientReceipt.screenshots | Sort-Object file) {
    $path = [Net.WebUtility]::HtmlEncode($capture.file.Replace('\','/'))
    $null = $gallery.Append('<figure><a href="' + $path + '"><img loading="lazy" src="' + $path + '" alt="' + $path + '"></a><figcaption>' + $path + '</figcaption></figure>')
}
$null = $gallery.Append('</main></html>')
[IO.File]::WriteAllText((Join-Path $clientEvidence 'index.html'), $gallery.ToString(), $encoding)
$destination = Join-Path $taskRoot ('build/delivery/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
[IO.Directory]::CreateDirectory($destination) | Out-Null
Copy-Item -LiteralPath $clientEvidence -Destination (Join-Path $destination 'in-game-evidence') -Recurse
$artifacts = @()
foreach ($source in @($taskJar, (Join-Path $DependencyRoot 'HomeCore/build/libs/homecore-1.12.0.jar'),
    (Join-Path $DependencyRoot 'HomeLink Storage/build/libs/homelink_storage-1.1.1.jar'),
    (Join-Path $DependencyRoot 'HomeLinkEnergy/build/libs/homelink_energy-0.4.0.jar'))) {
    $name = [IO.Path]::GetFileName($source)
    if ($source -ne $taskJar) {
        $expected = $clientReceipt.dependencies | Where-Object file -eq $name
        if (!$expected -or $expected.sha256 -ne (Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant()) {
            throw ('Dependency differs from the tested client: ' + $name)
        }
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $destination $name)
    $artifacts += @{ file = $name; bytes = (Get-Item -LiteralPath $source).Length;
        sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$coreUnits = 0
$coreEvidence = Join-Path $taskRoot 'build/validation/evidence/homecore-unit'
[IO.Directory]::CreateDirectory($coreEvidence) | Out-Null
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $DependencyRoot 'HomeCore/build/test-results/test') -Filter 'TEST-*.xml' -File) {
    [xml]$report = [IO.File]::ReadAllText($file.FullName)
    if ([int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0) { throw 'HomeCore test failures' }
    $coreUnits += [int]$report.testsuite.tests
    Copy-Item -LiteralPath $file.FullName -Destination $coreEvidence
}
$sourceManifest = @()
$sourcePaths = @(& git -C $taskRoot ls-files --cached --others --exclude-standard -- src scripts docs integration .github |
    Where-Object { Test-Path -LiteralPath (Join-Path $taskRoot $_) -PathType Leaf })
if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate sources' }
$sourcePaths += @('README.md','REPRISE.md','build.gradle','settings.gradle','gradle.properties')
foreach ($relative in $sourcePaths | Sort-Object -Unique) {
    $sourceManifest += @{ path = $relative.Replace('\','/');
        sha256 = (Get-FileHash -LiteralPath (Join-Path $taskRoot $relative) -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$result = @{ createdAt = (Get-Date).ToString('o'); minecraft = '1.21.1'; neoforge = '21.1.252'; java = '21.0.11';
    homecoreUnitTests = $coreUnits; checks = $checks; inGame = $clientReceipt; artifacts = $artifacts; sources = $sourceManifest;
    validationLimits = @('Synthetic benchmark is inventory-only, not a 20-client load test',
        'Third-party machines without adapters and JEI/REI are outside this integration');
    deliveryDirectory = $destination }
$json = ($result | ConvertTo-Json -Depth 10) + "`n"
[IO.File]::WriteAllText((Join-Path $taskRoot 'build/validation/release-receipt.json'), $json, $encoding)
[IO.File]::WriteAllText((Join-Path $destination 'release-receipt.json'), $json, $encoding)
$instructions = @'
HomeLink Tasks 0.1.0 — Minecraft 1.21.1 / NeoForge 21.1.252 / Java 21

HomeCore joint est obligatoire (1.12.0). Storage facultatif : 1.2.0 avec Energy 0.4.1.
Installer les mêmes JAR sur client et serveur. Ne garder qu'un JAR par mod.

Storage est facultatif. Si vous l'utilisez, conserver aussi HomeLink Energy joint.
Tasks n'ajoute aucun besoin énergétique. Les quatre JAR sont séparés ; aucune classe
HomeCore ou Storage n'est embarquée dans Tasks.

Le rapport validation.md décrit les vérifications exécutées et celles qui restent
manuelles. Aucune publication distante n'a été effectuée.
Les captures et journaux de la campagne en jeu sont dans in-game-evidence.
'@
[IO.File]::WriteAllText((Join-Path $destination 'README.txt'), $instructions + "`n", $encoding)
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs/validation.md') -Destination $destination
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs/user-guide.md') -Destination $destination
Copy-Item -LiteralPath (Join-Path $taskRoot 'docs/ux-refonte.md') -Destination $destination
Copy-Item -LiteralPath (Join-Path $taskRoot 'build/validation/visual-assets.json') -Destination $destination
$preview = @'
<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>HomeLink Tasks — nouvelle interface</title>
<style>body{font:17px/1.55 system-ui;background:#20262b;color:#e7e5e0;max-width:1000px;margin:40px auto;padding:0 24px}h1{font-size:32px}h2{font-size:23px;margin-top:40px}a{color:#d2b181}img{display:block;max-width:100%;height:auto;border:1px solid #626568}p{max-width:760px;color:#bfc5c9}</style>
<h1>Des tâches plus simples à utiliser</h1>
<p>Captures du jeu, avec le JAR livré. Créez une fabrication sans tenir d'objet en main, puis suivez sa progression.</p>
<h2>1. Rechercher l'objet</h2><p>Un nom, une icône et une ligne cliquable.</p>
<img src="in-game-evidence/host/ux-item-search.png" alt="Recherche d'un établi dans le sélecteur d'objets">
<h2>2. Indiquer la quantité</h2><p>Le nom est facultatif. La recette peut être prévisualisée avant validation.</p>
<img src="in-game-evidence/host/ux-craft-form.png" alt="Formulaire de fabrication de 16 établis">
<h2>3. Retrouver ses tâches</h2><p>À petite résolution, les onglets remplacent les colonnes pour garder les tâches lisibles.</p>
<img src="in-game-evidence/host/fr_fr-gui-4-0.png" alt="Tableau compact avec onglets d'état">
<h2>4. Voir ce qu'il manque</h2><p>La tâche affiche les objets fabriqués et les ingrédients nécessaires. Les réglages sont dans les menus secondaires.</p>
<img src="in-game-evidence/host/fr_fr-gui-4-4.png" alt="Détail de fabrication et ingrédients">
<p><a href="in-game-evidence/index.html">Toutes les captures et preuves</a> · <a href="user-guide.md">Guide utilisateur</a></p>
</html>
'@
[IO.File]::WriteAllText((Join-Path $destination 'apercu.html'), $preview, $encoding)
Write-Output ('Delivery: ' + $destination)
Write-Output ('Tasks SHA256: ' + $taskHash)
Write-Output ('HomeCore tests: ' + $coreUnits)
