# Format de sauvegarde et migrations

Les données vivent dans la sauvegarde serveur du mod, `homelink_tasks`, attachée à
l'Overworld. **Elles ne résident pas dans une BlockEntity** : casser un écran ne perd
rien.

Version de format actuelle : **3**, écrite dans le champ racine `Version`.

## Ce qui est sauvegardé

| Racine | Contenu |
|---|---|
| `Version` | version du format, pour les migrations |
| `Boards` | tableaux, membres, rôles, rattachement réseau, et leurs cartes |
| `Pins` | épingles, par UUID de joueur |
| `Tracked` | carte suivie, par UUID de joueur |
| `CreditedBatches` | identités de lots déjà crédités |
| `KnownPlayers` | UUID et derniers noms connus des joueurs |

### Un tableau

`Id`, `Title`, `Description`, `Owner`, `CreatedTick`, `Revision`, `Archived`,
`Network` (facultatif), `Members` (paires joueur/rôle ; le propriétaire est implicite),
`Cards`.

### Une carte

`Id`, `Type`, `Title`, `Description`, `Creator`, `CreatedTick`, `Revision`, `Priority`,
`Status`, `Archived`, `Order`, `NodeX`, `NodeY`, `Parent` (facultatif), `Assignees`,
`Dependencies`, `DerivedIngredient`, `Objective` (facultatif), `Log`.

### Un objectif de fabrication

`Target` (ItemStack complet, composants inclus), `TargetQuantity`, `Completed`, `Recipe`,
`Locked`, `Policy`, `ActivationTick`, `ActivationEpoch`, `ActivationOrder`,
`Contributions` (paires joueur/quantité).

`ActivationTick` est le tic serveur à partir duquel la carte suit les productions. Il est
conservé tel quel. Pour deux événements dans le même tick, l'époque d'exécution et
l'ordre de démarrage permettent de refuser un lot antérieur. L'établi persiste le même
marqueur avec sa session. Une égalité de tick sans preuve d'ordre est refusée de façon
conservatrice ; une ancienne sauvegarde ne reçoit pas de marqueur inventé.

### Le journal

Au plus 32 entrées par carte, les plus anciennes étant oubliées. Chaque entrée porte son
genre, son auteur éventuel, son tic et, pour un progrès regroupé, la quantité. Il n'y a
jamais une entrée par objet d'un lot.

## Ce qui n'est jamais sauvegardé

- aucun `Screen`, aucun `RecipeManager`, aucune référence d'entité, aucun `IItemHandler` ;
- aucun instantané client traité comme vérité permanente ;
- **aucune quantité de stock et aucune couleur de disponibilité**. Ce sont des
  observations temporaires, recalculées à la demande. Elles ne constituent jamais un
  inventaire persistant et ne prouvent aucune réservation.

## Migrations

`TaskStorageFormat.VERSION` porte la version. Au chargement :

- une version **inconnue et supérieure** à celle du code est refusée ; les données ne sont
  pas réécrites par une version plus ancienne du mod ;
- les formats 0 à 2 restent lisibles. Le format 2 ajoute les noms connus, les ingrédients
  dérivés et la conservation des objectifs non résolus ; le format 3 ajoute l'ordre
  d'activation. Les champs absents gardent les valeurs par défaut ;
- une entrée malformée est **ignorée**, jamais appliquée à moitié : un tableau sans
  identité est sauté, une adhésion violant un invariant est abandonnée, une dépendance
  devenue invalide n'est pas restaurée.

## Données devenues invalides

Si une recette ou un mod disparaît, la carte est **conservée** et reste identifiable. Elle
ne peut simplement plus être planifiée, ce qui est signalé au joueur. Supprimer
silencieusement le travail de quelqu'un serait pire que de l'afficher comme demandant une
attention.

Un objectif dont l'objet cible ne se résout plus est conservé sous forme NBT brute avec
sa carte, son titre, son historique et sa place. Il est réécrit sans perte afin de pouvoir
se résoudre si le mod revient, mais ne contribue pas tant qu'il est non résolu.

## Épingles et suivi

Les épingles sont persistantes par UUID de joueur **et par monde** : elles vivent dans la
sauvegarde de ce serveur, donc rejoindre un autre monde n'en montre aucune de l'ancien.

Au chargement, les épingles et les choix de suivi pointant vers une carte disparue sont
purgés. Retirer un membre purge aussi les siennes sur ce tableau.

## Limite connue

Le tableau expose au plus 256 cartes, archives incluses. Une ancienne sauvegarde ayant
plus de cartes conserve les entrées excédentaires dans les sauvegardes suivantes ; elles
ne sont ni affichées ni suivies. Après suppression de cartes et rechargement, les places
libérées permettent de relire ces entrées. Le débordement historique n'est pas perdu
silencieusement pour satisfaire la nouvelle borne réseau.

`CreditedBatches` garde au plus 4096 identités. Après éviction, un ancien reçu rejoué peut
être crédité de nouveau. Le contrat suppose une livraison proche de la production,
pas une garantie de déduplication éternelle.

Un arrêt brutal du serveur **entre** la sauvegarde du fichier du mod et celle de la chunk
contenant l'établi peut néanmoins perdre la trace d'un crédit. Aucune atomicité mondiale
n'est prétendue. Les sauvegardes et rechargements normaux, ainsi que la réémission d'un
reçu, sont couverts par les tests.

Aucun rattrapage de production n'a lieu pour le temps pendant lequel le monde n'a pas été
simulé.
