# Refonte UX — 1er octobre 2026

La création de fabrication fonctionne sans tenir un objet en main. Le choix se fait
par recherche dans les résultats des recettes synchronisées par le serveur, avec
icône et nom traduit. Les variantes de composants sont conservées.

| Avant | Après | Pourquoi |
|---|---|---|
| Objet imposé par la main du joueur | Recherche par nom, ligne cliquable avec icône | Choisir directement ce que l'on veut fabriquer |
| Plusieurs réglages dès la création | Objet, quantité, nom facultatif ; recette prévisualisable | Réduire les décisions nécessaires |
| Contribution limitée aux assignés par défaut, sans assigné | Tous les membres contributeurs pour les nouvelles tâches | Une tâche nouvellement créée peut progresser immédiatement |
| Quatre commandes principales et une commande d'écran | Ajouter une tâche, Projets et menu secondaire | Donner une priorité visuelle à l'action courante |
| Trois colonnes trop étroites à petite résolution | Liste pleine largeur avec onglets d'état | Rendre les noms et la progression lisibles |
| Nombreux boutons autour du détail | Affichage HUD, action principale et menu secondaire | Lire les besoins avant de régler des options |
| Nombreux réglages sur une seule page | Sections Détails, Équipe, Avancé | Montrer les contrôles utiles au bon moment |
| Sauvegardes séparées du titre et de la description | Un bouton Enregistrer, requêtes sérialisées par révision | Éviter les conflits entre les deux modifications |
| Recette suivante appliquée directement | Aperçu avant choix, annulation possible | Comprendre le changement avant de l'appliquer |
| Libellés coupés sans indication | Ellipse, infobulles et retours à la ligne adaptés | Signaler le texte abrégé sans débordement |
| Refus serveur uniquement dans le HUD caché par le menu | Notification visible dans l'en-tête | Expliquer pourquoi une action a échoué |
| Fonds industriels très contrastés, nombreuses bordures | Palette plus calme, action principale cuivre, onglet souligné | Clarifier la hiérarchie et la sélection |
| Calques physiques à la même profondeur | Fond, panneaux et texte à des profondeurs distinctes | Éviter le masquage et les interférences visuelles |
| Libellés de boutons et bandeau parfois masqués | Texte dessiné devant le fond | Stabiliser la lisibilité à toutes les tailles testées |
| HUD agrandi trop large pour une petite fenêtre | Largeur bornée et échelle verticale adaptée | Garder le suivi à l'intérieur de l'écran |

Les fonctions avancées restent disponibles : membres, rôles, assignations, liens,
carte mentale, matériaux du projet, réglages de contribution et archivage.
Les tâches archivées sont accessibles dans Paramètres → Avancé.

## Vérification

La campagne native utilise deux clients Minecraft avec la musique désactivée.
Elle saisit une recherche localisée au clavier, sélectionne un objet sans en tenir,
vérifie le refus de zéro comme quantité, crée un objectif, annule un aperçu de recette,
choisit une autre recette, modifie une tâche, termine et rouvre une tâche simple,
archive puis restaure une tâche depuis la liste des archives, puis annule et confirme une suppression.

Elle photographie 23 vues/états en français et anglais, à 640 × 360 et 320 × 240 pixels
logiques. Les captures contrôlent les limites et les chevauchements des widgets.
Les boutons et les noms de tâches abrégés dans le tableau proposent une infobulle.

Le rendu physique couvre les trois tailles et quatre orientations, les objets en main,
le suivi HUD agrandi dans les quatre coins d'une petite fenêtre, le déchargement/rechargement
des chunks et les droits distincts des joueurs.
Le second client vérifie qu'un membre peut prendre en charge une tâche depuis son détail,
avant les contrôles de modification concurrente et de révocation d'accès.
`verify-visual-assets.ps1` valide le décodage des 8 textures, les références et UV des
6 modèles, ainsi que les 171 clés de traduction par langue.

Les résultats et empreintes de la version livrée sont dans `release-receipt.json`.
Les captures sont consultables dans `in-game-evidence/index.html` du pack.
