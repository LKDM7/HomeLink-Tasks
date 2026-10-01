# Architecture

Le serveur décide, le client dessine. Aucune règle métier ne vit côté client, et aucun
paquet client affirmant qu'un joueur a fabriqué quelque chose n'est cru.

## Responsabilités

| Côté | Possède |
|---|---|
| Serveur | tableaux, membres, permissions, recettes utilisées, progression, reçus, stock, affectation des productions, persistance |
| Client | dessin, interactions, animations, préférences d'affichage et copies des données autorisées |

## Paquets

```
board        TaskBoard, BoardRole, BoardPermissions
task         TaskCard, TaskStatus, TaskPriority, TaskType,
             ActivityLog, ActivityEntry, CardTransitions, DependencyGraph
objective    CraftObjective, ContributionPolicy
recipe       RecipeResolver, VanillaRecipeAdapter
production   CraftTracker, CraftIndex, ReceiptDeduplicator, ContributionAllocator
stock        IngredientAvailabilityPlanner, AuthorizedStockQuery, StockContext,
             PlayerInventoryView, AvailabilityPlan, IngredientAvailability, AvailabilityState
server       TaskManager, TaskSavedData, TaskStorageFormat, RequestBudget
block        TaskDisplayBlock, TaskDisplayBlockEntity, TaskDisplayDevice
network      TaskPackets, TaskPayloads, TaskRequestHandler, CardRequestHandler,
             TaskSubscriptions, BoardView, CardView, PlanView
client       ClientTaskState, BoardScreen, MindMapScreen, CardDetailScreen,
             CardEditorScreen, BoardListScreen, PinnedTaskOverlay, TaskClientConfig
```

Les calculs de recettes, d'allocation, de permissions et de transitions sont testables
sans renderer : aucun d'eux n'a besoin d'un `Screen`, d'un monde ou d'un client.

## Le chemin d'une fabrication

```
Electronics Workbench : le lot se termine, le résultat existe
        │
        ▼  ProductionReceipt publié sur le canal HomeCore
TaskManager.onProduced
        │
        ▼  CraftIndex.candidates(produced)      ← indexé par objet voulu, pas de balayage
CraftTracker.credit
        │  ReceiptDeduplicator.claim(transactionId)   ← un rejeu ne crédite rien
        │  filtre : carte active avant le lot, résultat compatible,
        │           auteur admissible, droits valides
        ▼
ContributionAllocator.allocate
        │  1. carte explicitement suivie par le producteur
        │  2. puis ordre déterministe : carte la plus ancienne, puis identité
        │  3. chaque carte prend au plus son besoin restant
        │  4. le surplus descend, sans jamais dépasser le lot réel
        ▼
CraftObjective.credit → CardTransitions.applyProduction
                            première production → En cours
                            objectif atteint    → Terminé, une seule fois
```

Rien d'autre n'écrit dans ce compteur. Un glisser-déposer ne le touche jamais, ni pour
l'augmenter ni pour le remettre à zéro.

## Le chemin d'une disponibilité

```
Le client demande un plan pour une carte visible ou épinglée
        │
        ▼  RequestBudget.claim         ← cadence bornée, pas un paquet par frame
TaskManager.plan
        │  RecipeResolver.describe     ← résolu depuis le RecipeManager du serveur
        │  descriptor.operationsFor(remaining)
        │
        ├── PlayerInventoryView.countable(player)
        │       36 emplacements + main secondaire, chacun une seule fois
        │
        └── AuthorizedStockQuery.observe
                identité, droit sur le tableau, networkId, rattachement,
                permission VIEW, portée réelle
                        │
                        ▼  StockProvider.observe sur chaque Controller du réseau
                StockContext.merge      ← fusion par StockSourceId canonique
        ▼
IngredientAvailabilityPlanner.plan
        flot borné : inventaire moins cher que stock,
        une unité ne satisfait qu'un besoin
        ▼
AvailabilityPlan → PlanView → ce client uniquement
```

### Pourquoi un flot et pas un compte par ingrédient

Compter chaque ingrédient séparément se trompe dès que deux besoins se chevauchent. Une
planche de chêne satisfait « une planche de chêne » **et** « une planche quelconque »,
mais pas les deux à la fois : un compte séparé afficherait vert alors que le plan est
insuffisant.

Un choix glouton a le défaut symétrique. Dépenser l'unique planche de chêne sur le besoin
large fait échouer un plan qui avait pourtant une solution.

L'allocation est donc résolue **globalement**, comme un flot borné des piles d'objets vers
les besoins. Les arêtes venant de l'inventaire coûtent moins que celles venant du stock,
ce qui maximise d'abord la faisabilité puis privilégie les objets du joueur. Les deux cas
ci-dessus sont couverts par des tests.

Au-delà des bornes (`MAX_BUCKETS`, `MAX_UNITS`), le planificateur renvoie un état **non
vérifié** plutôt que de bloquer le serveur ou d'inventer un vert ou un rouge.

## Concurrence

Toute mutation porte la révision que le client croyait éditer. Deux joueurs déplaçant la
même carte en même temps ne créent ni copie, ni double appartenance à deux colonnes, ni
perte d'édition : le second reçoit un conflit explicite et l'état à jour.

Le joueur agissant est toujours le joueur authentifié de la connexion. Aucun UUID fourni
dans un paquet n'est traité comme une identité agissante.

Les abonnements Tasks vivent sur un canal dédié et n'écrasent jamais l'abonnement
Dashboard de HomeCore.

## Performances

- le suivi des crafts est indexé par objet voulu ; aucun balayage de tous les tableaux ;
- les disponibilités ne sont recalculées que pour les cartes visibles ou épinglées, sur
  changement, avec une cadence maximale bornée par joueur ;
- aucun scan de monde, aucun rescan de coffres, aucun chunk chargé de force ;
- huit unités de calcul au plus par tick serveur pour le HUD et les requêtes réunis ;
  un plan projet consomme quatre unités, un plan carte une unité ;
- HUD étalé sur 20 ticks selon l'identité du joueur, avec réactions aux productions ;
- aucun cache de stock partagé entre joueurs. Les droits sont revérifiés avant chaque
  observation ; les copies client expirent après 25 ticks, celles du plan projet après
  2,5 secondes. Les abonnements d'écran revérifient accès et contexte chaque tick.

Bornes actuelles : 32 tableaux par propriétaire, 256 cartes par tableau, archives incluses, 32
membres, 16 dépendances par carte, titre 80 caractères, description 2000, journal 32
entrées, 3 tâches épinglées par défaut (1 à 5), 64 variantes par requête de stock, 16
fournisseurs consultés, 4096 identités de lot mémorisées, expansion limitée à 128 nœuds
et profondeur 8. Un snapshot futur ou vieux de plus de 200 ticks est rejeté côté Tasks.

Le protocole réseau est en version 3 : client et serveur doivent utiliser le même JAR.
Le plan projet alloue toute sa frontière dans un flot commun, puis publie les provenances
par besoin. Il s'agit d'une estimation réutilisable, sans réservation ni optimum garanti.
