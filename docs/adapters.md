# Contrat des adaptateurs

HomeLink Tasks ne réimplémente ni HomeCore ni Storage. Il consomme trois contrats publics
et neutres portés par HomeCore **1.12.0** (API publique **1.8.0**), et n'importe aucune
classe interne de Storage : ni `StorageIndex`, ni `StorageBlockEntity`, ni leurs
inventaires.

Ces contrats ne dépendent pas de la présence de Tasks. L'établi électronique et Storage
continuent de fonctionner sans lui.

---

## A. Lecture autorisée et non destructive du stock

`fr.lkdm.homecore.api.stock`

Storage publie `StockProvider.CAPABILITY` sur son Controller, comme une `DeviceCapability`
HomeCore ordinaire. Tasks lit ce contrat et rien d'autre.

```java
StockProvider.CAPABILITY          // DeviceCapability<StockProvider>, id homecore:stock_provider
StockSnapshot observe(StockRequest request)
```

`StockRequest` porte un `networkId`, le **joueur authentifié** pour qui la réponse est
calculée et au plus `StockRequest.MAX_VARIANTS` (64) variantes. Passer une identité ici
n'en accorde aucune : le fournisseur revalide à chaque appel l'appartenance, la permission
`VIEW` sur ce réseau exact, le rattachement de l'appareil et sa propre portée. Un échec
renvoie `StockSnapshot.unavailable`, jamais les données d'un autre réseau.

### Fraîcheur et périmètre

`StockAvailability` sépare trois faits :

| Valeur | Signification |
|---|---|
| `COMPLETE` | tout le périmètre a été observé ; une variante absente est réellement absente |
| `PARTIAL` | une partie n'a pas pu être observée ; une absence ne prouve rien |
| `UNAVAILABLE` | rien n'a été observé. **Inconnu n'est pas zéro.** |

`StockAccess` sépare lire et récupérer : `READ_ONLY` signifie que le joueur voit la
quantité sans pouvoir la retirer, et Tasks l'affiche ainsi au lieu de promettre une
récupération.

Le `StockSnapshot` porte aussi la révision du fournisseur et le tic serveur de
l'observation, pour l'invalidation de cache et l'affichage de la fraîcheur.
Storage rapporte l'âge de la plus ancienne observation valide, vérifie les chunks
déjà chargés et les identités de couverture, et écarte les connexions trop anciennes
(deux intervalles de rescan, au minimum 20 ticks). Tasks refuse en plus les snapshots
futurs ou âgés de plus de 200 ticks. Une fréquence Storage supérieure à cette borne
peut donc produire du gris jusqu'à l'observation suivante. Aucun cache privé n'est
partagé entre joueurs.

### Déduplication des sources

Chaque `StockEntry` attribue ses quantités à des `StockSourceId` **canoniques** :
dimension + position de l'inventaire logique. Storage utilise exactement l'identité de son
propre index, c'est-à-dire `InventoryConnection.inventoryPos` — la première moitié d'un
double coffre, par exemple.

Conséquence directe : deux Controllers qui couvrent le même coffre de 8 diamants
renvoient la **même** identité de source. Tasks fusionne par source en gardant la plus
grande observation, jamais en additionnant. Le réseau possède 8 diamants, pas 16.

Si une identité canonique ne peut pas être produite, la source n'est pas rapportée du
tout, plutôt que sommée à l'aveugle.

### Absence d'effet de bord

Lire n'extrait rien, ne déplace rien, ne réserve rien, ne force aucun chunk à charger et
ne déclenche aucun rescan complet parce qu'une carte est visible. Les quantités sont
indicatives à l'instant de l'observation : un autre joueur peut les consommer entre la
consultation et la fabrication.

---

## B. Description publique des recettes

`fr.lkdm.homecore.api.recipe`

```java
RecipeDescriptors.register(RecipeType<?> type, RecipeDescriptorProvider provider)
Optional<RecipeDescriptor> RecipeDescriptors.describe(RecipeHolder<?> holder)
```

Le mod propriétaire d'un type de recette enregistre son adaptateur une seule fois ;
HomeCore le fait pour `homecore:electronics` via `ElectronicsDescriptors`. Tasks fournit
son propre adaptateur pour les recettes `shaped` et `shapeless` de la table de craft, qui
produit le même `RecipeDescriptor`.

Un type sans adaptateur renvoie un `Optional` vide. Ce cas est affiché « Recette non
prise en charge ». Il n'est jamais deviné.

### Quantités

Les quantités d'un `RecipeDescriptor` décrivent **une opération** : `ingredients()` est
consommé une fois et `result()` porte le rendement.

```
remaining      = max(0, targetQuantity - completedQuantity)
operations     = ceil(remaining / outputPerOperation)      // descriptor.operationsFor()
requiredPerIng = countPerOperation × operations
```

Il reste 10 objets et la recette en produit 4 : il faut 3 opérations, donc 12 objets
produits dont 2 en surplus.

`maxBatchOutput()` reste la limite du producteur. La demande finale d'une tâche ne change
ni la limite de lot de l'établi ni ses règles de rendement.

Les prédicats `Ingredient` réels sont conservés : tags, alternatives et contraintes du
type de recette. Une recette acceptant toutes les planches n'est pas affichée manquante
parce que le joueur possède de l'épicéa plutôt que du chêne.

---

## C. Notification d'un lot réellement produit

`fr.lkdm.homecore.api.production`

```java
ProductionLog log = DashboardAPI.production(server);
log.subscribe(receipt -> ...);
log.publish(receipt);
```

L'établi électronique publie au **moment exact** où le résultat apparaît dans son
emplacement de sortie, dans `ElectronicsBlockEntity.tick()`, juste après que les items
existent et avant que la session soit libérée.

```java
ProductionReceipt(
    UUID transactionId,                 // identité stable du lot
    UUID producer,                      // joueur sous l'autorité duquel le lot a démarré
    ResourceLocation source,            // homecore:electronics_workbench
    Optional<ResourceLocation> recipe,
    ItemStack result,                   // count = quantité d'OBJETS FINIS
    long startedTick, long completedTick, long sequence,
    Optional<UUID> network,
    Optional<ProductionStart> start)    // époque + ordre du démarrage
```

### Ce qui n'émet aucun reçu

Un lot annulé ou remboursé n'émet rien : `refund()` ne passe jamais par ce point. Un
prototype validé mais non finalisé n'émet rien. Reprendre trois fois l'objet fini dans le
slot de sortie n'émet rien de plus. Déplacer un item, le retirer de Storage, l'échanger,
le ramasser, `/give` et le menu créatif n'émettent rien.

Un prototype validant 64 composants rapporte **64**, pas 1 : `result.getCount()` porte la
quantité réelle du lot.

### Ordre et rejeu

`startedTick` et `completedTick` sont des tics serveur : ils avancent de façon monotone et
ne sont pas modifiés par `/time set`. Tasks compare `startedTick` à l'activation de sa
carte et refuse tout crédit rétroactif d'un lot antérieur. `sequence` ordonne les reçus
émis pendant le même tic, à l'intérieur d'une exécution du serveur. `startStamp()`
attribue aussi un ordre au démarrage et à l'activation d'un objectif, afin de distinguer
ces événements dans le même tick. L'époque est unique par exécution ; elle accompagne
l'ordre dans la sauvegarde. Un ordre de completion seul ne suffit pas à cette comparaison.

L'établi conserve l'auteur et l'identité du lot en cas de fermeture ou de rechargement
normal : `session.player`, `session.id`, `session.startedTick` et le marqueur de démarrage
sont persistés. Une
production validée qui se termine pendant l'absence du joueur est attribuée à son auteur
enregistré, jamais au joueur qui vient récupérer le résultat.

Le canal ne conserve aucun historique. Un consommateur doit créditer chaque
`transactionId()` **au plus une fois**, car un reçu peut être délivré de nouveau.
`ReceiptDeduplicator` s'en charge côté Tasks, avec une mémoire bornée et persistée.

---

## Limite honnête de la déduplication

`ReceiptDeduplicator` garde au plus 4096 identités, persistées avec le reste des données
du mod. La livraison doit rester proche de la production : après éviction d'une identité,
un ancien reçu peut à nouveau être crédité.

En revanche, un arrêt brutal du serveur entre deux sauvegardes de fichiers distincts —
la sauvegarde du mod et celle de la chunk contenant l'établi — peut perdre la trace d'un
crédit. Aucune atomicité mondiale n'est prétendue ici. Les sauvegardes et rechargements
normaux, eux, sont couverts par les tests.
