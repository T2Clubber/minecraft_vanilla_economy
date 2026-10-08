# VanillaEconomy

Plugin Paper pour **Minecraft 26.3**. Il ajoute une monnaie virtuelle, un marché global tenu par les idiots du village et des cités protégées. Toute la logique tourne côté serveur : les joueurs n'ont aucun mod à installer, seul un petit resource pack optionnel donne son apparence à la pièce.

## Prérequis

- Paper 26.3 (testé sur le build 152, y compris en chargement « legacy » des plugins).
- Java 25.

## Installation sur le serveur

1. Copier `VanillaEconomy-1.0.0.jar` dans `plugins/`, puis démarrer le serveur. Paper télécharge lui-même le driver SQLite au premier lancement.
2. Configurer le resource pack de la pièce dans `server.properties` :

   ```properties
   resource-pack=https://raw.githubusercontent.com/T2Clubber/minecraft_vanilla_economy/main/dist/VanillaEconomy-pack.zip
   resource-pack-sha1=<sha1 affiché par tools/build_pack.py>
   require-resource-pack=false
   ```

   Le pack s'ajoute par-dessus les packs du joueur, il ne les remplace pas. Un joueur qui le refuse voit simplement une pépite d'or à la place de la pièce.
3. Gérer la liste blanche. En 26.3, un nouveau serveur est créé avec `white-list=true` : il faut ajouter les joueurs avec `/whitelist add <pseudo>`, ou passer `white-list=false`.

## Commandes

### Joueurs

| Commande | Effet |
|---|---|
| `/solde` (alias `/balance`, `/bal`) | Affiche le solde |
| `/pay <joueur> <montant>` | Transfère de l'argent à un joueur du même monde, à 10 blocs maximum. Le transfert est atomique |
| `/money drop <montant>` | Convertit une partie du solde en pièces physiques (chaque retrait crée une nouvelle série signée, 2304 maximum par retrait) |
| `/money deposit` | Crédite sur le solde les pièces tenues en main principale, après vérification de la signature et du registre anti-dupe |

### Administration (permission `vanillaeconomy.admin`, op par défaut)

| Commande | Effet |
|---|---|
| `/marketadmin rotate` | Force une nouvelle rotation du marché |
| `/marketadmin rotate promo [%]` | Force une rotation en promotion (remise aléatoire de 5 à 50 % si aucun pourcentage n'est donné) |
| `/marketadmin promos` | Affiche le planning des promotions d'aujourd'hui et de demain |
| `/marketadmin discordtest` | Envoie un message de test au webhook Discord |
| `/marketadmin info <item>` | Affiche l'état d'un item : catégorie, prix, promo, stock, circulation |
| `/marketadmin setstock <item> <quantité>` | Fixe le stock global d'un item |
| `/eco give <joueur> <montant>` | Donne des pièces sur le solde d'un joueur, même hors ligne s'il est déjà venu sur le serveur |
| `/eco take <joueur> <montant>` | Retire des pièces, sans jamais passer le solde sous 0 |
| `/eco set <joueur> <montant>` | Fixe un solde exact |

### Permissions

| Permission | Défaut | Rôle |
|---|---|---|
| `vanillaeconomy.solde`, `vanillaeconomy.pay`, `vanillaeconomy.money` | tous | Commandes joueur |
| `vanillaeconomy.market` | tous | Utiliser les idiots marchands |
| `vanillaeconomy.admin` | op | `/marketadmin`, `/eco` |
| `vanillaeconomy.notify` | op | Recevoir les alertes de pièces falsifiées ou dupliquées |
| `cities.use` | tous | `/city`, `/cityboard`, `/border` |
| `cities.admin` | op | `/cityadmin` |
| `cities.admin.bypass` | op | Ignorer la protection des territoires |

## Les idiots marchands

Tout idiot du village adulte devient un marchand. Ça couvre les idiots générés dans les villages, et ceux nés d'une reproduction, qui deviennent marchands à l'âge adulte. Ils gardent leur IA et leur cycle de vie vanilla : s'ils meurent ou sont zombifiés, ce point d'accès au marché disparaît.

- **Clic droit** : interface **Achat**, où l'idiot vend. **Sneak + clic droit** : interface **Vente**, où l'idiot achète.
- **Disposition** :
  - la première ligne affiche les 9 articles de la rotation : 1 minerai, 3 nourriture / nature, 1 butin de monstre, 3 blocs, 1 divers ;
  - la seconde ligne affiche le solde, une horloge indiquant le temps avant la prochaine rotation, et le bouton qui bascule vers l'autre interface.
- **Clic** : à la vente, la plus petite quantité qui rapporte une pièce ; à l'achat, ce que vaut une pièce (ou un seul objet s'il coûte plus d'une pièce). **Shift-clic** : jusqu'à une stack à l'achat, tout ce qui fait des pièces entières à la vente.
- **Jamais d'arrondi en faveur du joueur** :
  - à la vente, l'idiot ne paie que des pièces entières et ne prend que les objets payés. Avec 48 cannes à sucre à 1 pièce les 32, il en achète 32 pour 1 pièce et le joueur garde les 16 autres ;
  - à l'achat, le joueur paie toujours au moins la valeur exacte, arrondie à la pièce supérieure. Le shift-clic s'arrête à la plus grande quantité qui tombe sur des pièces entières : 60 objets pour 3 pièces plutôt que 64 pour 4.
- **Inventaire libre** : pendant que l'interface est ouverte, le joueur peut déplacer, diviser ou jeter les objets de son propre inventaire. Seuls le shift-clic et le double-clic vers l'interface sont bloqués.
- Avec un name tag ou une laisse en main, le clic garde son comportement vanilla.

## Le marché

- **Global :** prix et stock sont communs à tous les idiots. Seul le choix des articles proposés dépend du biome (du skin) de l'idiot.
- **Rotation :** toutes les 2 h, calée sur l'horloge (00 h, 02 h… 22 h) du fuseau `timezone` de `config.yml` (`Europe/Paris` par défaut), quel que soit le fuseau de l'hébergeur. Un article proposé à la vente n'est jamais proposé à l'achat en même temps, sur tout le serveur.
- **Prix de rachat :** ce que l'idiot paie au joueur. Il baisse avec la quantité vendue au marché, sans jamais descendre sous le plancher de 1 pièce pour 64 unités. Le compteur de quantité vendue diminue de 2 % par rotation, ce qui permet aux prix de remonter.
- **Prix de vente :** ce que l'idiot facture au joueur. Il vaut toujours au moins le prix de rachat + le plancher.
- **Stock :** il est alimenté par les ventes des joueurs. La rotation de l'interface Achat favorise les articles en stock, et ceux en rupture restent visibles mais grisés.
- **Promotions :**
  - fréquence : chaque jour, 2 des 12 rotations sont en promotion. Les créneaux et les remises (de 5 à 50 %) sont tirés au hasard et planifiés à l'avance, pour aujourd'hui et demain ;
  - interface Vente : la quantité de référence (`base_number`) est réduite de la remise, uniquement pour les articles dont le `base_number` dépasse 1 : l'idiot paie donc plus cher par unité ;
  - interface Achat : le prix devient `max(prix de rachat, prix de vente × (1 - remise))` ;
  - affichage : les articles concernés brillent, et le badge « ✦ PROMO -X% ✦ » s'affiche en titre de l'infobulle, au-dessus du nom de l'item. X est la remise réelle sur le prix : côté Vente, elle est égale à la remise annoncée, car le lot réduit n'est pas arrondi ; côté Achat, elle est plafonnée par le prix de rachat. L'ancien prix est barré, et l'horloge signale la promo en cours.
- **Annonce Discord :** quand la rotation suivante est une promo, le plugin poste une annonce « Nitwit » sur Discord, avec le pourcentage et l'horaire. Il passe par un webhook, donc sans bot ni hébergement. L'URL se met dans `config.yml`, section `discord`, et un rôle peut être mentionné.
- **Journal staff Discord :** avec un second webhook (`discord.logs_webhook_url`), le plugin poste dans le salon staff les pièces falsifiées ou dupliquées refusées, les commandes `/eco`, `/marketadmin` et `/cityadmin`, la création, l'agrandissement et la dissolution des cités, les `/pay` à partir de 1 000 pièces (`logs_pay_threshold`), ainsi que le démarrage et l'arrêt du serveur.
- **Annonces en jeu :** à chaque rotation, un message signé « [Nitwit] » dans le chat annonce les nouveaux étals, l'heure de la prochaine rotation et, le cas échéant, la promo en cours. Il se désactive avec `market.broadcast_rotation`.

Les réglages sont dans `plugins/VanillaEconomy/config.yml` : markup, courbe de prix, decay, promos (`per_day`, `min_percent`, `max_percent`), filtres d'articles par biome, et chance qu'un bébé né d'une reproduction devienne idiot.

## Les cités

Une cité est un territoire carré, sur toute la hauteur du monde, fondé avec `/city create <nom>` et centré sur le fondateur. La fondation coûte 2 500 pièces, prélevées sur le solde du fondateur. La cité démarre en 32×32 et s'agrandit par paliers, payés avec le solde de la cité : 64×64 (5 000), 128×128 (20 000), 256×256 (50 000), 512×512 (100 000). Les valeurs sont réglables dans `cities.yml`.

- **Rôles :**
  - **Propriétaire** : gère tout, achète les paliers, dissout la cité. Un joueur ne peut posséder qu'une cité.
  - **Co-propriétaire** : ajoute et exclut des membres simples.
  - **Membre** : construit, contribue, peut quitter la cité. Un joueur peut être membre de plusieurs cités.
- **Accès :** une cité n'est jamais fermée. Les visiteurs entrent et circulent librement, mais en lecture seule :
  - ils ne peuvent ni construire, ni casser, ni ouvrir de conteneur, ni utiliser boutons, leviers ou plaques ;
  - ils peuvent ouvrir les portes et les trappes, et utiliser les coffres de l'Ender (stockage personnel), sans jamais utiliser l'objet tenu en main ;
  - ils peuvent toujours commercer avec les idiots marchands.
- **Protections automatiques :** explosions sans destruction de blocs, pas de propagation du feu, pistons et liquides bloqués à la frontière, pas de grief des mobs.
- **Messages :** l'entrée et la sortie d'une cité s'affichent dans le chat, et un visiteur reçoit un rappel en actionbar quand une action lui est refusée.
- **Trésorerie :** chaque versement fait avec `/city contribute` est enregistré. À la dissolution, le solde est remboursé au prorata des contributions.
- **Notifications :** les joueurs concernés sont prévenus quand ils sont ajoutés ou exclus, nommés ou retirés co-propriétaire, quand leur cité est dissoute ou supprimée, et du montant de leur remboursement. Le propriétaire est aussi prévenu des versements et des départs de ses membres. Un joueur hors ligne reçoit ces messages à sa prochaine connexion : ils sont conservés en base (table `city_notification`) et survivent aux redémarrages.
- **Performance :** les protections et les messages s'appuient sur un index spatial en mémoire, sans aucune requête en base dans les events.

### Commandes des cités

| Commande | Effet |
|---|---|
| `/city create <nom>` | Fonde une cité centrée sur le joueur (coût prélevé sur son solde) |
| `/city info [nom]` | Informations sur une cité |
| `/city add <joueur> [cité]` | Ajoute un membre ; les joueurs hors ligne déjà venus sur le serveur sont acceptés |
| `/city kick <joueur> [cité]` | Exclut un membre |
| `/city coowner set\|unset <joueur>` | Nomme ou retire un co-propriétaire |
| `/city contribute <nom> <montant>` | Verse des pièces du solde personnel au solde de la cité |
| `/city upgrade [confirm]` | Achète le palier suivant |
| `/city leave <nom>` | Quitte une cité (impossible pour le propriétaire) |
| `/city disband [confirm]` | Dissout la cité et rembourse son solde |
| `/cityboard <nom>` | Tableau de bord des membres : onglets Membres, Paliers, Trésorerie |
| `/border <nom> on\|off` | Affiche les bordures de la cité en particules, visibles par ce seul joueur |
| `/cityadmin info\|delete\|settier\|rename` | Administration |

Les alias de `/city` sont `/cite` et `/ville`.

## Fichiers générés dans `plugins/VanillaEconomy/`

- `config.yml` : monnaie, `/pay`, paramètres du marché et des promos, filtres de pool par biome.
- `cities.yml` : coût de création, mondes autorisés, écart minimal entre cités, paliers, protections, messages d'entrée et de sortie, bordures.
- `messages.yml` : textes des cités (MiniMessage, variables `{city}`, `{player}`…).
- `items.yml` : catalogue des articles (catégorie, tier, `base_price` pour `base_number` unités) et répartition des slots. Si la répartition change, la rotation est retirée automatiquement au démarrage.
- Les **données** ne sont pas dans ce dossier mais **dans le monde** : `world/vanillaeconomy/economy.db` (selon `level-name`). Un nouveau monde démarre donc une économie neuve, et une sauvegarde du monde contient ses soldes, cités et marché. Les versions précédentes gardaient la base dans `plugins/VanillaEconomy/` : elle est déplacée automatiquement dans le monde actuel au premier démarrage.
- `economy.db` : base SQLite avec les tables `player_balance`, `coin_serial`, `market_item_state`, `market_rotation`, `villager_instance`, `city`, `city_member`, `city_contribution`, `city_notification` et `meta`.

### Articles ajoutés en 26.3

| Item | Catégorie | Prix de base |
|---|---|---|
| `SHELF_MUSHROOM`, `RED_SHRUB` | food_nature | 2 pièces / 32 |
| `POPLAR_LOG` | blocks | 2 pièces / 64 |
| `RED_POPLAR_LEAVES`, `ORANGE_POPLAR_LEAVES`, `YELLOW_POPLAR_LEAVES` | blocks | 1 pièce / 64 |

## Resource pack (texture de la pièce)

La pièce est une pépite d'or portant `custom_model_data = 1001`. Les valeurs 1002 et 1003 sont les icônes de navigation des interfaces : la pièce avec un « A » vert mène à l'Achat, la pièce avec un « V » rouge mène à la Vente. Le pack (`resourcepack/`) couvre les formats 88 à 99, ce qui inclut la 26.2 (88) et la 26.3 (97.1).

Pour régénérer la texture à partir de l'image source :

```bash
python3 tools/convert_coin.py art/coin_model.jpeg resourcepack/assets/vanillaeco/textures/item/piece.png
```

Pour régénérer les icônes A et V :

```bash
python3 tools/make_icons.py
```

Pour construire `dist/VanillaEconomy-pack.zip` et afficher son SHA-1 :

```bash
python3 tools/build_pack.py
```

Après chaque modification du pack, il faut committer le zip puis mettre à jour `resource-pack-sha1` dans `server.properties`. Sinon, les clients gardent l'ancienne version en cache.

## Développement

Le build exige un JDK 25 :

```bash
JAVA_HOME=/chemin/vers/jdk-25 mvn package
```

Le jar produit est `target/VanillaEconomy-1.0.0.jar`. Les tests unitaires (prix, rotation, promotions, règles des cités) sont lancés par `mvn package`.
