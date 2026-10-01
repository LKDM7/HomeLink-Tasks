# Guide utilisateur

## Poser un écran

Fabriquez un **Écran de tâches** et posez-le contre un mur. Clic droit pour l'ouvrir.
Les tailles petit, moyen et grand affichent le même projet, avec un contenu live
personnel. Les grands cadres débordent de leur bloc d'ancrage : laissez de la place au mur.

Pour le rattacher à un réseau, utilisez le **HomeLink Connector** de HomeCore, exactement
comme pour n'importe quel autre appareil : Maj + clic droit sur un appareil du réseau pour
le sélectionner, puis clic droit sur l'écran. Ce mod n'ajoute aucun outil de liaison.

Un écran non rattaché fonctionne : les indications de matériaux portent alors sur votre
inventaire seulement, et l'écran le dit.

## Créer un projet

Ouvrez **Projets**, saisissez un nom et validez. Vous en êtes propriétaire.

L'écran d'accueil liste les projets que vous pouvez ouvrir, avec leur avancement et leur
nombre de membres, et une recherche. Un écran vide vous explique quoi faire plutôt que de
rester blanc.
Depuis un écran rattaché, seuls les projets compatibles avec ce contexte sont proposés.
Le menu **… → Paramètres** regroupe les sections Général, Membres et Avancé. Le réseau se règle dans Avancé depuis un écran physique.

## Ajouter des joueurs

Le propriétaire invite depuis les joueurs connus du serveur, et le mod stocke leur UUID :
changer de pseudo ne casse rien, et un membre hors ligne reste membre. Aucun service
externe n'est appelé.

| Rôle | Peut |
|---|---|
| OWNER | tout, y compris les membres et la suppression |
| EDITOR | créer, modifier, assigner, ordonner, archiver les cartes |
| MEMBER | contribuer, réclamer une tâche libre, déplacer ses propres cartes |
| VIEWER | consulter et épingler pour son propre HUD |

Une invitation au tableau **ne crée aucune permission HomeNetwork**. Si un membre n'a pas
le droit `VIEW` sur le réseau du tableau, la partie stock lui apparaît en gris : il voit
le projet, pas le contenu du réseau.

Retirer un membre coupe immédiatement ses abonnements et purge ses épingles.

## Les deux types de carte

**Tâche libre.** Vous écrivez ce que vous voulez, y compris « Construire le bâtiment ».
Seul un joueur la termine, en la déplaçant. Poser ou casser des blocs ne la validera
jamais.

**Fabrication.** Ouvrez **Ajouter une tâche → Fabriquer un objet → Choisir un objet**.
Recherchez son nom et cliquez sur sa ligne avec icône. Indiquez la quantité puis cliquez
sur **Créer**. Aucun objet en main n'est nécessaire. Le nom est facultatif : celui de
l'objet est utilisé par défaut. **Recette … · Voir** permet de vérifier les ingrédients
et de choisir une autre recette avant de valider.

La quantité est un nombre d'**objets finis**, pas de clics, d'opérations ni de prototypes
électroniques. Un lot d'établi validant 64 composants compte 64.

### Qui peut contribuer

- **Tous les membres contributeurs** : valeur proposée pour les nouvelles tâches.
- **Joueurs assignés uniquement** : option dans les réglages avancés de fabrication.
  Sans joueur assigné, personne ne contribue. Les tâches existantes gardent leur réglage.

### Quelle recette compte

La recette choisie sert au **plan**. Par défaut, toute recette prise en charge donnant le
même résultat admissible contribue. Vous pouvez verrouiller la carte sur la recette
sélectionnée si vous voulez le contraire.

## Le tableau

Trois états : **À faire**, **En cours**, **Terminé**. Sur une petite fenêtre, ils deviennent
des onglets pour garder des tâches lisibles. Sur une grande fenêtre, ils apparaissent
côte à côte. Glissez une carte d'une
colonne à l'autre, ou utilisez les flèches gauche et droite, ou les boutons de déplacement
dans le menu **…** du détail de la tâche : tout ce qu'un glisser fait est accessible sans glisser.

L'archivage est une propriété distincte, pas une quatrième colonne : une carte archivée
garde l'état qu'elle avait.

### Ce qu'un glisser ne peut pas faire

- déposer dans **Terminé** un objectif dont le compteur est incomplet ;
- ramener dans **À faire** une carte qui a déjà produit quelque chose ;
- remettre un compteur à zéro, dans aucun sens.

Le serveur explique le refus et l'affichage revient à l'état réel.

Une carte terminée peut être **recommencée** : cela crée une nouvelle carte à zéro et ne
rembobine jamais celle qui est finie.

## Actions courantes

Cliquez sur une tâche pour l'ouvrir. **Afficher à l'écran** ajoute son suivi personnel au HUD. **Prioriser ce craft** donne la priorité à cette fabrication pour vos prochains lots ; cette action est distincte de l'affichage HUD. Une tâche simple propose directement **Terminer**.

**Modifier** regroupe le titre et la description dans Détails, les assignations dans Équipe, et l'archivage, la suppression et les liens dans Avancé. Le bouton Retour ou Échap revient à l'écran précédent. Les suppressions demandent confirmation.

## La carte mentale

Même projet, autre vue. Les nœuds sont les **mêmes cartes** : un renommage, une
assignation ou une fabrication apparaît dans les deux vues en même temps.

Déplacez la vue, zoomez entre ×0,5 et ×2, déplacez les nœuds, recentrez, clic droit sur un
nœud pour l'ouvrir.

Un lien **parent** signifie « sous-tâche de ». Une **dépendance** signifie « attend que ».
Les deux sont distingués visuellement et les cycles sont refusés. Une dépendance non
terminée donne un badge « En attente » — jamais une colonne, et jamais un refus de
créditer une fabrication réelle.

Un parent manuel n'est pas terminé parce que ses enfants le sont. Une carte de fabrication
parent n'avance que lorsque **son** objet est réellement produit.

Dans la vue recette, **Créer une sous-tâche** ouvre un aperçu du composant sélectionné :
choisissez sa variante et une recette compatible, examinez quantité et ingrédients,
puis créez la sous-carte. Elle commence à zéro. Le développement refuse cycles et
doublons ; il s'arrête à huit niveaux et 128 nœuds. Une quantité ou recette ayant déjà
des composants développés reste protégée contre les changements qui invalideraient le plan.

**Matériaux du projet** montre la frontière développée et une allocation commune de
votre inventaire et du Storage autorisé. Un composant développé remplace son besoin
dans le parent. Les quantités sont indicatives et ne réservent aucun objet.

## Les couleurs

Chaque état porte aussi un symbole et un texte : la couleur n'est jamais seule.

| | État | Signification |
|---|---|---|
| ✔ | Vert | tout est dans **votre** inventaire |
| ◆ | Orange | il manque quelque chose, mais un stock autorisé et vérifié le couvre |
| ✖ | Rouge | les sources vérifiées ne suffisent pas ; le manque est chiffré |
| ? | Gris | un Storage configuré est indisponible ou incomplet, ou la recette n'est pas prise en charge |

Quelques cas concrets, pour un besoin de 10 cuivres :

| Inventaire | Storage | Résultat |
|---|---|---|
| 10 | 0 | ✔ vert, 10/10 |
| 6 | 4 vérifiés | ◆ orange, 6 inventaire + 4 Storage / 10 |
| 6 | 2, périmètre complet | ✖ rouge, 8/10, il manque 2 |
| 6 | configuré, hors ligne | ? gris, 6 connus, stock non vérifiable |
| 10 | hors ligne | ✔ vert |
| 6 | aucun Storage installé | ✖ rouge, 6/10, inventaire seulement |

Fabio et Alex ouvrent la même carte. Fabio a les ingrédients sur lui : il voit vert. Alex
ne les a pas : il voit orange si le Storage autorisé contient son manque. La carte et son
compteur de production restent pourtant communs.

**Aucun de ces changements ne fait progresser une fabrication.** Quand les matériaux
arrivent dans Storage, rouge devient orange. Quand vous les récupérez, orange devient
vert. La carte ne bouge pas pour autant.

Si vous pouvez voir un stock sans pouvoir le retirer, l'écran affiche « Présent dans
Storage — récupération restreinte » plutôt que de vous promettre l'objet.

## Épingler et suivre

Ce sont **deux choses différentes**.

L'**étoile** épingle la carte sur votre HUD, pour vous seul. Elle ne change l'affectation
d'aucune fabrication.

« **Suivre cette fabrication** » choisit la carte que vos propres lots terminés créditent
en priorité. C'est ce bouton, et lui seul, qui décide de l'ordre.

## Le HUD

Une fois l'interface fermée, vos tâches épinglées restent visibles : titre, progression ou
état, et l'indication de matériaux calculée **pour vous**.

Réglages côté client : visibilité, mode compact, échelle, coin, contraste renforcé,
réduction des animations, durée de conservation d'une tâche terminée, et nombre préféré
d'épingles (1 à 5, 3 par défaut ; le serveur applique aussi sa propre limite).

Une observation trop ancienne passe en gris ; les compteurs de fabrication restent visibles.
Le HUD respecte F1 et l'échelle de GUI, et ne capture jamais la souris pendant le jeu. Une
touche configurable (K par défaut) ouvre le tableau épinglé, via une requête autorisée :
elle ne donne aucun accès caché.

Les épingles sont persistantes par joueur **et par monde**. Aucune carte du serveur
précédent ne reste visible après connexion à un autre monde.

## Si une recette disparaît

Après un `/reload` ou un changement de datapack, les recettes sont revérifiées. Si celle
d'une carte a disparu, la carte et ses progrès sont conservés, le problème est signalé, et
vous pouvez choisir une autre recette compatible. Il n'y a ni crash ni remise à zéro
silencieuse.
