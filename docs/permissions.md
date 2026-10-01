# Permissions

Deux systèmes de droits coexistent et ne se remplacent **jamais** l'un l'autre :

- les **rôles de tableau**, propres à ce mod ;
- les **permissions HomeNetwork** de HomeCore.

Une invitation à un tableau ne crée aucune permission réseau, et une permission réseau ne
rend membre d'aucun tableau.

## Rôles de tableau

| Rôle | Gérer le tableau | Éditer les cartes | Déplacer | Contribuer | Épingler |
|---|---|---|---|---|---|
| OWNER | oui | oui | toutes | oui | oui |
| EDITOR | non | oui | toutes | oui | oui |
| MEMBER | non | non | les siennes | oui | oui |
| VIEWER | non | non | aucune | non | oui |

**Déplacer « les siennes »** : une carte à laquelle le joueur est assigné, ou une tâche
libre qu'il a créée.

**« Je m'en occupe »** permet à un MEMBER ou plus de réclamer une carte **non assignée** et
de la passer en cours.

Le propriétaire est unique et apparaît toujours avec le rôle OWNER. Il ne peut pas être
retiré, et aucun autre membre ne peut recevoir ce rôle.

## Contribuer à une fabrication

Un lot n'est crédité à une carte que si **toutes** ces conditions tiennent :

1. la carte était active avant le démarrage du lot (`startedTick >= activationTick`) ;
2. le résultat correspond à la variante voulue, composants compris ;
3. l'auteur du lot est admissible selon le mode de contribution ;
4. l'auteur est membre du tableau et n'est pas VIEWER ;
5. la carte n'est ni archivée ni déjà terminée ;
6. si la carte est verrouillée sur une recette, c'est bien celle-là ;
7. le reçu n'a pas déjà été traité.

En mode **joueurs assignés uniquement** sans personne d'assigné, personne ne contribue.
La carte le signale explicitement.

## Lire un stock

Avant chaque requête, le serveur vérifie, dans cet ordre :

1. l'identité du joueur — toujours celle de la connexion authentifiée, jamais un UUID reçu
   dans un paquet ;
2. son droit de **voir le tableau** ;
3. le `networkId` du contexte — un tableau rattaché à un réseau n'en lit jamais un autre ;
4. le **rattachement du fournisseur** à ce même réseau ;
5. la permission HomeCore **`VIEW`** sur ce réseau exact ;
6. la **portée et l'accessibilité réelles** appliquées par le fournisseur lui-même.

Un réseau partagé n'autorise pas automatiquement tous ses membres à consulter chaque
inventaire : les restrictions propres au fournisseur s'appliquent aussi.

Un échec ne renvoie pas un résultat plus petit : il renvoie **inconnu**. Le client ne
reçoit jamais de données qu'il masquerait ensuite graphiquement.

## Lire n'est pas retirer

La lecture est strictement en lecture seule : aucune extraction, aucun déplacement, aucun
autocraft, aucune réservation de slot. Une planification virtuelle n'est pas une
transaction d'inventaire.

Le droit de **retrait** est distinct et correspond à `CONTROL` côté HomeCore. Si un joueur
peut voir un stock sans pouvoir le récupérer, l'écran affiche « Présent dans Storage —
récupération restreinte ». Tasks ne contourne jamais `CONTROL`.

## Rattacher un tableau à un réseau

Rattacher demande **à la fois** :

- d'être propriétaire du tableau ;
- la permission HomeCore `MANAGE_NETWORK` sur le réseau visé.

Le réseau visé est celui de l'écran utilisé pour faire la demande, résolu côté serveur.
Un client ne peut pas nommer un réseau arbitraire.

## Rattacher un écran

L'écran est un appareil HomeCore ordinaire. Il se rattache avec le **HomeLink Connector**
existant, qui applique ses propres règles : le droit de configurer la machine
(propriétaire, opérateur, ou `CONFIGURE` sur son réseau) et `MANAGE_NETWORK` sur les deux
réseaux concernés.

## Révocation

Retirer un membre :

- coupe son abonnement au tableau ;
- purge ses épingles et son choix de suivi portant sur ce tableau ;
- lui renvoie immédiatement un état sans ce tableau.

Les données **cessent d'être envoyées**. Elles ne sont pas seulement masquées côté client.
