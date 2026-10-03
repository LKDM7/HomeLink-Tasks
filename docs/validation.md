# Validation de HomeLink Tasks

## Migration du kit UI — Tasks 0.2.0 / HomeCore 1.14.0

Le build, les 78 tests unitaires et le contrôle du JAR ont été exécutés le
3 octobre 2026, sans échec. Le serveur GameTest sans Storage a exécuté ses
40 tests requis avec succès ; le profil avec Storage et Energy a réussi ses
46 tests requis. Les logs locaux sont `build/ui-kit-build.log`,
`build/ui-kit-gametest.log` et `build/ui-kit-gametest-storage.log`. Ils concernent le checkout de migration ;
ils n’indiquent aucune publication Maven ou validation distante.

Le parcours client disponible utilise `runInGame -PguiReview`, avec
`-PguiReviewLanguage=fr_fr` ou `en_us`. Il couvre 23 vues aux fenêtres
1280 × 720 et 640 × 480, aux GUI scales 2 et 3, ainsi que les scénarios
et écrans physiques existants. Chaque capture d’un TaskScreen vérifie les
contrôles visibles, leurs bornes et chevauchements, et la tabulation à travers
tous les contrôles actifs et visibles. Les deux parcours ont réussi le
3 octobre 2026 : marqueur `TASKS_IN_GAME_OK` à 16:35:21 en anglais et
16:39:32 en français. Chaque langue a conservé 82 captures et son journal sous
`build/validation/ui-kit-en` ou `build/validation/ui-kit-fr`. La revue visuelle
a inspecté les 23 petites vues de chaque langue, ainsi que deux vues anglaises
en grande fenêtre et au GUI scale 3, sans défaut visuel bloquant observé.

The UI migration build passed 78 unit tests, the release-JAR checks and
40 required dedicated-server GameTests without Storage and 46 with Storage/Energy. The client review
supports explicit French/English selection, two window sizes, GUI scales 2/3
and a complete active/visible widget tab cycle. Both client runs passed on
October 3, 2026, with 82 captures archived per language. Visual review covered
all 23 small views in each language and two additional English large/scale-3
views, with no blocking visual defect observed. These local results do not
claim a remote CI run or Maven publication.

## Résultats historiques de la reprise

État du chantier au 1er octobre 2026, après refonte UX. Tasks consomme les contrats publics
de HomeCore 1.12.0 / API 1.8.0 (stock, recettes, reçus de production). Ils sont publiés : le
fournisseur de stock de HomeLink Storage est livré dans Storage 1.2.0. Aucun patch n'est plus
appliqué aux dépendances.

Environnement : Windows 11, Microsoft OpenJDK 21.0.11, Gradle 8.8,
Minecraft 1.21.1, NeoForge 21.1.252. Les fixtures de développement restent hors du JAR.

## Résultats automatisés

La reprise a obtenu **75 tests unitaires**, **39 GameTests sans Storage** et
**45 GameTests avec Storage** (HomeCore 1.12.0, Storage 1.1.1 patché, Energy 0.4.0), sans échec,
le 1er octobre 2026. Les résultats définitifs et empreintes sont conservés dans
`build/validation/release-receipt.json`.

| Vérification | Preuve |
|---|---|
| Tests unitaires | `build/test-results/test/TEST-*.xml` |
| HomeCore | Build complet, Javadoc et tests de la version publiée 1.12.0 |
| Storage | Build complet et vérification du JAR de la version 1.2.0 |
| Serveur avec HomeCore, Tasks, Storage et Energy réels | `build/validation/gametest/logs/latest.log`, marqueur `All … required tests passed` |
| Deux processus, format 3 | `build/validation/evidence/persistence-write-final` puis `persistence-read-final`, marqueur `TASKS_PERSISTENCE_READ_OK` |
| Archive de distribution | `verifyReleaseJar`, exécuté par `check` |
| Mesure comparative synthétique | `build/validation/benchmark.json`, marqueur `TASKS_SYNTHETIC_BENCHMARK_OK` |

Commandes reproductibles, après configuration de `JAVA_HOME` vers un JDK 21 :

```powershell
./gradlew.bat build runGameTestServer --offline
./gradlew.bat build runGameTestServer --offline -PwithStorage
./gradlew.bat runGameTestServer --offline -PpersistencePass=write
./gradlew.bat runGameTestServer --offline -PpersistencePass=read
```

La CI récupère HomeCore, Storage et Energy à des commits épinglés de leurs versions publiées
et exécute ces profils sans dépendance au Maven local.

## Portée des preuves

Les unités couvrent les transitions, permissions, contributions, partage déterministe
d'un lot entre cartes, rejeu, sauvegarde, expansion et allocation globale des ingrédients.
Un inventaire de 6 unités et un stock de 4 ne peuvent satisfaire deux demandes de 10
chacune. La limite de 256 cartes inclut les archives. Une expansion respecte la limite
de quantité du serveur et refuse cycles, doublons et excès de profondeur ou de nœuds.

Les GameTests utilisent les vrais menus de craft et le vrai établi HomeCore : prise
simple, Maj+clic, inventaire plein et presque plein, résultat jeté, aperçu non prélevé,
annulation, finalisation de 64 objets GUI fermé, rechargement d'un lot validé et
prélèvements ultérieurs sans nouveau crédit. Ils vérifient aussi un lot démarré avant une
nouvelle carte dans le même tick : les 64 objets sortent, la nouvelle carte reste à zéro.

Les fixtures Storage emploient les vrais Controller, Link et tonneaux. Elles couvrent
rouge → orange → vert sans production, deux Controllers sur une source, les deux moitiés
et six faces d'un double coffre, VIEW sans CONTROL, retrait d'un membre, autre réseau,
absence d'énergie, observation absente ou trop ancienne et couverture supprimée.
Les blocs d'une fixture restent dans le même chunk, car le Link couvre des chunks.
L'observation lit l'index existant ; elle ne retire rien et ne déclenche pas de scan de slots.

Les tests d'écran couvrent l'identité HomeCore, le Connector, le refus d'accès et la
survie des tableaux après cassage. Le contrôle d'archive vérifie les trois tailles, les
quatre orientations, les JSON, les bornes des modèles, un seul objet rendu au cassage,
les textures et l'égalité des clés françaises et anglaises. Cela ne mesure pas le rendu
visuel : celui-ci fait désormais l'objet de la campagne décrite ci-dessous.

## Mesure comparative

Le baseline est le planificateur du point de pause conservé dans l'archive du 30 septembre,
SHA-256 `5bd82b49e95013edf4d72eba015bc1482515e1297423892c73273f9c6dadfd5b`.
Le générateur ne change que son nom et son paquet pour pouvoir charger les deux versions.
Le test les exécute dans la même JVM, après échauffement, sur 300 plans et vérifie des
résultats identiques. Il mesure temps et allocations du thread, puis encode et décode le
vrai payload HUD. Le fichier JSON conserve les chiffres de chaque exécution.

La charge est **synthétique : 20 identités, trois épingles chacune, neuf ingrédients
chevauchants, inventaire uniquement**. Elle n'émule pas vingt connexions Minecraft et ne
mesure pas les scans Storage. L'étalement réduit le pic périodique de 60 à 3 calculs par
tick ; le plafond global reste de huit unités par tick pour HUD et requêtes réunis.
Un plan projet consomme quatre unités afin de rester possible avec trois épingles HUD
recalculées dans le même tick ; au plus deux plans projet peuvent donc être servis.
Le payload mesuré pour 60 épingles est de
3730 octets par période de 20 ticks, hors enveloppe réseau et notifications d'événements.
Les temps JVM varient entre exécutions ; aucun gain de latence en multijoueur n'est affirmé.

Exécution de référence du 30 septembre avec Storage chargé, avant la refonte UX
(la charge mesurée reste limitée à l'inventaire ; les mesures de chaque nouvelle passe
restent dans son `benchmark.json`) :

| Mesure sur 300 plans | Baseline | Candidat |
|---|---|---|
| Temps du thread | 4,034 ms | 4,380 ms |
| Octets alloués | 3 622 152 | 3 629 352 |
| Pic périodique HUD simulé | 60 calculs/tick | 3 calculs/tick |

Le refactoring ajoute ici 24 octets par plan ; le gain mesuré porte sur l'étalement de
la charge périodique, et non sur l'accélération du solveur individuel.

## Revue de l'interface

La refonte du 1er octobre est décrite dans [ux-refonte.md](ux-refonte.md) : recherche
d'objet sans objet en main, formulaire court, actions secondaires regroupées, détails
et réglages par sections, vue compacte à onglets et calques des écrans physiques corrigés.
Les vérifications antérieures ci-dessous restent applicables aux fonctions conservées.

| Avant | Après | Pourquoi |
|---|---|---|
| Barre d'actions trop large sur petit écran | Boutons répartis dans la largeur ; réseau dans les réglages du tableau | Garder les actions accessibles à petite résolution |
| Détail long tronqué et contrôles superposés | Zone de contenu défilante et pieds de page séparés | Lire description, ingrédients et journal sans recouvrement |
| Déplacements proposés à des rôles qui seront refusés | Contrôles désactivés selon rôle, assignation et progression | Montrer les actions réellement permises |
| Expansion choisissant implicitement un composant | Aperçu avec choix de variante et recette, confirmation liée à la révision | Examiner les sous-cartes avant création |
| Liste des matériaux sans allocation entre lignes | Allocation commune avec inventaire, Storage, manque et restriction | Ne pas compter une pile plusieurs fois |
| Disponibilité mémorisée après changement de contexte | Expiration à 25 ticks et invalidation de dimension ou d'accès | Ne pas présenter une observation ancienne comme actuelle |
| Fond redessiné par `Screen.render` après les textes | La base commune rend seulement les widgets après le contenu | Les cartes, détails et légendes restent visibles dans Minecraft 1.21.1 |
| Accents français corrompus et priorités sans traduction | UTF-8 réparé, quatre priorités FR/EN et contrôle du JAR | Supprimer les clés techniques et caractères corrompus à l'écran |
| Rôle superposé au titre des membres | Rôle affiché dans son bouton avec infobulle | Garder les informations distinctes à 320 × 240 |
| Renommage vide avec options de fabrication | Titre prérempli, bouton Renommer et réglages limités au contexte | Éviter une interface trompeuse et conserver la saisie au redimensionnement |
| Nœuds hors de la zone visible et badge sur la progression | Cadrage initial adapté au viewport ; indicateur d'attente avec infobulle | Lire les cartes et leur progression même en petite résolution |
| Compte agrégé répété dans chaque case de recette | Une unité par case occupée d'une recette mise en forme | Un établi demande quatre planches au total, pas quatre par case |
| Texte physique trop fin à quelques blocs | Canevas 128 / 192 / 256 selon la taille | Privilégier des lignes lisibles ; les grands écrans montrent davantage de cartes |

## Campagne dans les vrais clients

`scripts/verify-in-game.ps1` construit le JAR puis lance deux JVM Minecraft distinctes,
TasksAlice et TasksBob, avec musique désactivée. Le client principal héberge un monde
plat neuf ; le second rejoint réellement le serveur par TCP. Ne pas lancer un autre
profil Gradle simultanément : les profils partagent le dossier de classes de validation.

Les preuves sont dans `build/validation/evidence/in-game-final` : journaux des deux
clients, captures du framebuffer et reçu SHA-256 lié au JAR. Le script échoue si un
client ne termine pas son parcours. Les scénarios et fixtures restent hors distribution.
La dernière campagne couvre **126 captures**, avec le JAR Tasks de 436 512 octets,
SHA-256 `5f2676296bf3cf28375fa984faa7da4ba93fb5374843fca24f19f4f3f6d1f316`.
`in-game-evidence/index.html` dans le pack permet de consulter la galerie.

- Vingt-trois vues/états en FR et EN, à 640 × 360 et 320 × 240 unités GUI : 92 captures et
  contrôle des limites et chevauchements des widgets. Glisser-déposer et flèches du clavier dans le Kanban.
- Parcours de création par les widgets réels : mains vides, recherche du nom localisé
  au clavier, sélection, refus de quantité nulle, conservation de la saisie après aperçu,
  création de 16 établis à zéro. Modification du titre et de la description en une sauvegarde,
  choix effectif d'une autre recette, fin/réouverture d'une tâche simple,
  archivage/restauration depuis la liste des archives, suppression annulée puis confirmée.
- Audit de 8 PNG, 6 modèles et 171 clés de traduction par langue : `visual-assets.json`.
- Création du tableau et des cartes, dépendance et déplacement des nœuds par les vrais
  paquets ; refus de terminer un objectif incomplet. Une prise du résultat dans le vrai
  menu de fabrication fait progresser l'objectif de 0 à 1 sur 16.
- Inventaire vert, matériaux manquants rouges, véritable Storage orange et arrêt
  d'alimentation gris, puis retour orange. La fixture fournit les matériaux et maintient
  l'alimentation par le port énergétique public ; elle ne remplace pas le provider Storage.
- Trois tailles physiques dans les quatre orientations, puis trois objets en main.
- Déchargement réel des chunks, absence de stock divulgué ou de chargement forcé,
  retour du stock après rechargement et conservation de l'écran, du projet et du crédit.
- Deux clients simultanés : disponibilités personnelles différentes, épingles et suivi
  indépendants, modification reçue par l'autre client, refus d'une révision périmée,
  puis retrait du tableau, des épingles et du suivi après révocation.

La campagne couvre ces parcours déterministes ; elle n'est pas une mesure de charge
à vingt joueurs ni une validation de tous les modpacks et périphériques d'entrée.

Les menus tiers sans adaptateur de recette, JEI/REI, cuisson, brassage et machines tierces
restent hors suivi automatique. Le journal de reçus est borné à 4096 identités : un rejeu
après éviction peut être crédité de nouveau. Un arrêt brutal entre sauvegardes distinctes
n'offre aucune atomicité mondiale. Aucun commit, push, tag ou publication n'a été effectué.
