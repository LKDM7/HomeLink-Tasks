# HomeLink Tasks 0.1.0

Tableaux collaboratifs, carte mentale, vraies recettes, suivi de fabrication et HUD
épinglé pour une base HomeLink.

Minecraft **1.21.1**, NeoForge **21.1.252**, Java **21**.
HomeCore **1.12.0** (API publique **1.8.0**) est obligatoire.

## La règle du mod

> Le stock indique si les ingrédients sont disponibles.
> La fabrication réelle fait progresser l'objectif.
> La possession d'un item ou la pose d'un bloc ne termine rien.
> Le tableau est collaboratif ; le stock autorisé et le HUD restent personnels.

## Créer une tâche de fabrication

**Ajouter une tâche → Fabriquer un objet → Choisir un objet** : recherchez son nom,
sélectionnez sa ligne avec icône, indiquez la quantité puis cliquez sur **Créer**.
Vous pouvez prévisualiser la recette et donner un nom personnalisé. Il n'est plus
nécessaire de tenir l'objet en main. Les options avancées sont regroupées dans les menus
secondaires ; les petites fenêtres utilisent des onglets d'état pour garder les tâches lisibles.

Voir le [guide utilisateur](docs/user-guide.md) et la [refonte UX](docs/ux-refonte.md).

## Construire

Configurer `JAVA_HOME` vers un JDK 21, puis :

```powershell
./gradlew.bat build
```

Le JAR est écrit dans `build/libs/`.

Le build utilise les sources adjacentes de `../HomeCore` quand elles sont présentes et à la
bonne version ; sinon (ou avec `-PuseLocalDependencies=false`) il résout HomeCore 1.12.0
publié sur GitHub Packages. Les contrats consommés, y compris `ProductionStart`, font partie
de HomeCore 1.12.0 publié. Le Maven local n'est pas nécessaire.

```powershell
./gradlew.bat test              # tests unitaires
./gradlew.bat runGameTestServer # vérifications sur un vrai serveur, puis sortie
./gradlew.bat runClient         # client de développement
./gradlew.bat build runGameTestServer -PwithStorage # Storage et Energy réels
```

## Ce que fait la V1

Créer un tableau → ajouter des joueurs → créer des cartes → afficher le tableau ou la
carte mentale → organiser À faire / En cours / Terminé → consulter les recettes et les
matériaux → fabriquer réellement → voir la progression s'actualiser toute seule.

Exactement **deux** types de carte :

- **MANUAL** : une tâche libre. Seul un changement manuel la termine. Un joueur peut
  écrire « Construire le bâtiment », mais poser des blocs ne la validera jamais.
- **CRAFT** : un objectif de fabrication. Sa progression vient uniquement des lots
  réellement terminés et validés par le serveur.

### Hors périmètre, volontairement

Aucune validation en posant ou cassant des blocs, aucun suivi automatique de
construction, aucune progression par possession, ramassage, retrait dans un coffre ou
livraison d'items, aucune caisse de livraison, aucune fabrication automatique depuis
Tasks, aucun retrait ni aucune réservation de matériaux dans Storage, aucune dépendance
énergétique ajoutée.

Les récurrences, échéances, récompenses et systèmes RPG sont hors V1.

## Les distinctions qui comptent

### Tableau partagé, inventaire personnel

Une carte et son compteur de production sont communs à tout le tableau. La couleur des
ingrédients, elle, est calculée **pour le joueur qui regarde**, à partir de son propre
inventaire. Deux joueurs devant la même carte voient donc légitimement des couleurs
différentes, sans que le compteur diffère.

### Même HomeNetwork, pas même serveur Minecraft

« Le même serveur » signifie ici le même hub HomeLink et son HomeNetwork logique. Pour
lire un stock depuis un écran, le tableau, l'écran et les fournisseurs interrogés doivent
porter le **même** `networkId`. Un mélange implicite entre deux réseaux est refusé.

### Matériaux disponibles, objets réellement fabriqués

Voir les matériaux ne fait rien progresser. Rouge peut devenir orange quand les
matériaux arrivent dans Storage, puis vert quand le joueur les récupère : aucun de ces
changements ne crédite la moindre fabrication et n'a jamais déplacé une carte.

### Stock inconnu, stock nul

Gris n'est pas rouge. Un Storage configuré mais hors ligne, sans énergie, en
reconstruction ou hors de portée donne un résultat **inconnu**, affiché en gris avec les
quantités connues. Un zéro n'est affirmé que lorsque tout le périmètre a réellement été
observé.

## Le code couleur

Chaque état porte aussi un symbole et un texte traduit : la couleur n'est jamais seule à
transporter l'information.

| | État | Signification |
|---|---|---|
| ✔ | **Vert** — Dans ton inventaire | La part nécessaire est allouée entièrement depuis l'inventaire du joueur, dans un plan sans double comptage. |
| ◆ | **Orange** — Disponible via Storage | L'inventaire ne suffit pas, mais un stock autorisé, actuel et non compté deux fois couvre le manque. |
| ✖ | **Rouge** — Matériaux manquants | Les sources vérifiées du périmètre ne suffisent pas. Le manque est indiqué. |
| ? | **Gris** — Stock non vérifiable | Un Storage configuré est indisponible, incomplet ou trop ancien, ou la recette n'est pas prise en charge. |

Sans Storage installé ou configuré, un manque est **rouge avec « inventaire seulement »**,
jamais une panne grise permanente. Si l'inventaire suffit à tout, l'état est vert même
lorsque Storage est hors ligne.

## Affectation d'un craft entre plusieurs cartes

Une unité produite est un objet réel : elle ne peut être comptée qu'une fois.

Deux cartes demandant chacune 64 câbles ne gagnent pas 16 chacune quand 16 câbles sont
fabriqués : **ensemble** elles gagnent 16. Le répartiteur sert d'abord la carte que le
producteur suit explicitement, puis les autres cartes admissibles dans un ordre
déterministe (carte la plus ancienne d'abord, puis identité de carte). Chaque carte reçoit
au plus son besoin restant, et le surplus descend la liste jusqu'à épuisement du lot.

**Épingler n'est pas suivre.** L'étoile met la carte sur le HUD de ce joueur. « Suivre
cette fabrication » est un bouton distinct, et c'est lui seul qui décide de la priorité
d'affectation.

## Recettes prises en charge

- recettes `shaped` et `shapeless` normales de la table de craft, y compris dans la grille
  2×2 de l'inventaire ;
- recettes `homecore:electronics`, avec ingrédients comptés, rendement et établi requis.

Une recette spéciale, dynamique ou d'un type sans adaptateur public est affichée
« Recette non prise en charge ». Elle n'est jamais devinée. Cuisson, brassage, enclume,
métiers et machines tierces sont hors suivi automatique en V1.

Après `/reload` ou un changement de datapack, les recettes sont revérifiées. Si une
recette a disparu, la carte et ses progrès sont **conservés** et le problème est signalé ;
il n'y a ni crash ni remise à zéro silencieuse.

## Restrictions d'accès

Une invitation à un tableau ne crée aucune permission HomeNetwork et ne donne jamais
accès à Storage. Avant toute requête de stock, le serveur vérifie l'identité du joueur,
son droit de voir le tableau, le `networkId` du contexte, le rattachement du fournisseur,
la permission HomeCore `VIEW` et la portée réelle.

La lecture est en lecture seule : aucune extraction, aucun déplacement, aucun autocraft,
aucune réservation de slots. Une permission de lecture n'est pas une permission de
retrait : si le joueur peut voir un stock sans pouvoir le récupérer, l'écran affiche
« Présent dans Storage — récupération restreinte ». Tasks ne contourne jamais `CONTROL`.

Retirer un membre coupe ses abonnements et purge ses épingles : les données cessent
d'être envoyées, elles ne sont pas seulement masquées côté client.

## Rôles

| Rôle | Peut |
|---|---|
| OWNER | tout, y compris les membres et la suppression |
| EDITOR | créer, modifier, assigner, ordonner, archiver les cartes |
| MEMBER | contribuer, réclamer une tâche libre, déplacer ses propres cartes |
| VIEWER | consulter et épingler pour son propre HUD |

## Le Task Display

Un seul objet écran HomeLink mural, avec les trois formats de HomeLink Dashboard :
**2 × 2**, **2 × 1 horizontal** et **1 × 1**. Comme un tableau, la pose choisit le plus
grand format qui tient dans l'espace libre. **Maj pendant la pose** force le 1 × 1.
Il conserve le même boîtier et les mêmes textures que le Dashboard. Contenu live réservé
à chaque observateur autorisé. Clic droit pour ouvrir le projet sélectionné. Chaque écran possède une
identité et un propriétaire persistants, s'intègre à HomeCore comme appareil et se
rattache avec le **HomeLink Connector existant** : ce mod n'ajoute aucun outil de liaison.

Le bouton **Réseau de l’écran**, dans la liste des projets ou les paramètres avancés,
permet également de choisir un réseau HomeCore puis **Connecter l’écran**. La liaison
utilise `DashboardAPI.bindDevice` et vérifie les droits sur l’appareil et les réseaux.
**Relier le projet** reste une action distincte, pour utiliser les stocks de ce réseau.
Avec le Connector : clic droit dans l’air pour choisir le réseau, puis sur l’écran.

Lorsqu’un joueur autorisé à configurer l’écran y ouvre ou crée un projet, ce choix est
mémorisé pour l’affichage mural. Un écran sans projet indique **Aucun projet choisi** ;
**Écran privé** est réservé au refus d’accès au projet. Le chargement, le refus d’accès
au réseau et un réseau différent ont chacun leur propre message.

Chaque partie ouvre le même projet et accepte le Connector. Retirer les blocs derrière
l’écran ne le fait pas tomber. Casser une partie de l’écran retire l’ensemble de l’écran.
Plusieurs écrans peuvent afficher le même tableau. Casser un écran rend exactement un
objet et ne supprime ni tableau, ni carte, ni permission ; l'objet obtenu ne contient
aucune copie cachée. Aucun chunk n'est chargé de force.

Recette :

```
I G I      I : lingot de fer (4)      B : HomeLink Circuit Board
B M C      G : vitre (1)              M : HomeLink Microprocessor
I R I      R : poudre de redstone (1) C : HomeLink Communication Module
```

Les anciennes variantes moyenne et grande restent chargeables pour les mondes existants,
et donnent l'écran standard lorsqu'elles sont cassées. Elles
n'ont plus de recette ni d'entrée créative. Les réglages du tableau permettent aussi
de rattacher ou détacher son réseau.

Dans **Paramètres → Affichage**, chaque joueur peut afficher ou masquer son HUD et le
contenu des écrans muraux, régler la position, la taille, le mode compact, le contraste
et la conservation des tâches terminées. Ces préférences ne changent pas les permissions.

Les fenêtres reprennent les dimensions du GUI Dashboard : surface de 520 × 340 au
maximum, centrée et réduite selon la taille de la fenêtre Minecraft.
Elles reprennent aussi son cadre biseauté, son bandeau, ses boutons gris en relief,
ses champs de 18 pixels et ses couleurs de sélection.
Dans la colonne **Terminé**, une petite poubelle rouge supprime directement la tâche
pour les propriétaires et éditeurs. Elle ne déplace pas la carte et n’ouvre pas sa fiche.

Le raccourci **Consulter le projet** est assignable dans les
commandes Minecraft, catégorie HomeLink Tasks. Il ouvre le dernier projet consulté,
ou le projet d'une tâche épinglée si aucun projet n'est ouvert. La liste donne accès aux
recettes et aux quantités disponibles/manquantes, sans déplacement, suppression ni
modification de tâche. Échap revient à la liste puis au jeu.

## Développer un plan

Dans le détail d'une tâche et dans son aperçu de recette, le bouton **+**, à droite
de chaque ingrédient fabricable, crée et
épingle sa sous-tâche avec la quantité requise. Si plusieurs recettes existent, choisir
la variante et la recette dans l'aperçu. Un ingrédient déjà développé ne peut pas être
ajouté une seconde fois. Si la limite d'épinglage est atteinte, la tâche reste créée
dans le projet. La sous-carte commence à
zéro ; ni stock ni création du plan ne créditent de production. L'expansion est bornée
à huit niveaux et 128 nœuds, refuse les cycles et respecte la quantité maximale du serveur.
Le plan matériaux remplace les composants développés par leurs propres ingrédients et
alloue les quantités dans un calcul commun, sans compter une pile sur deux lignes.
Il reste une estimation, sans réservation de stock.

## Prérequis de versions

| Mod | Version | Nécessité |
|---|---|---|
| HomeCore | 1.12.0 (API 1.8.0) | obligatoire |
| HomeLink Storage | 1.2.0 (avec HomeLink Energy 0.4.1) | facultatif — sans lui, le calcul porte sur l'inventaire seulement |
| JEI / REI | — | facultatif, simples raccourcis ; recettes, suivi et couleurs fonctionnent sans eux |

HomeCore 1.12.0 ajoute trois contrats publics neutres que ce mod consomme et ne
réimplémente pas : lecture de stock autorisée, description publique des recettes et reçus
de production. Ils sont décrits dans le README de HomeCore et dans
[docs/adapters.md](docs/adapters.md).

## Documentation

- [Guide utilisateur](docs/user-guide.md)
- [Architecture](docs/architecture.md)
- [Contrat des adaptateurs](docs/adapters.md)
- [Format de sauvegarde et migrations](docs/save-format.md)
- [Rapport de validation](docs/validation.md)
