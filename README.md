# VanillaEconomy

Plugin Paper pour **Minecraft 26.3** (compatible 26.2). Il ajoute une monnaie virtuelle et un marché global tenu par les idiots du village. Toute la logique tourne côté serveur : les joueurs n'ont aucun mod à installer, seul un petit resource pack optionnel donne son apparence à la pièce.

## Prérequis

- Paper 26.3 (testé sur le build 142) ou Paper 26.2 (testé sur le build 129).
- Java 25.

Le même jar et le même resource pack fonctionnent sur les deux versions. Sur un serveur 26.2, les items propres à la 26.3 sont ignorés au chargement d'`items.yml`, avec un avertissement dans les logs.

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
4. Optionnel : pour accepter à la fois des clients 26.2 et 26.3 sur un serveur 26.2, installer le plugin [ViaVersion](https://modrinth.com/plugin/viaversion) (5.12.0 ou plus récent).

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
| `/marketadmin rotate promo` | Force une rotation avec des prix promotionnels |
| `/marketadmin info <item>` | Affiche l'état d'un item : catégorie, prix, promo, stock, circulation |
| `/marketadmin setstock <item> <quantité>` | Fixe le stock global d'un item |

### Permissions

| Permission | Défaut | Rôle |
|---|---|---|
| `vanillaeconomy.solde`, `vanillaeconomy.pay`, `vanillaeconomy.money` | tous | Commandes joueur |
| `vanillaeconomy.market` | tous | Utiliser les idiots marchands |
| `vanillaeconomy.admin` | op | `/marketadmin` |
| `vanillaeconomy.notify` | op | Recevoir les alertes de pièces falsifiées ou dupliquées |

## Les idiots marchands

Tout idiot du village adulte devient un marchand. Ça couvre les idiots générés dans les villages, et ceux nés d'une reproduction, qui deviennent marchands à l'âge adulte. Ils gardent leur IA et leur cycle de vie vanilla : s'ils meurent ou sont zombifiés, ce point d'accès au marché disparaît.

- **Clic droit** : interface **Achat**, où l'idiot vend. **Sneak + clic droit** : interface **Vente**, où l'idiot achète.
- **Disposition** :
  - la première ligne affiche les 9 articles de la rotation : 1 minerai, 3 nourriture / nature, 1 butin de monstre, 3 blocs, 1 divers ;
  - la seconde ligne affiche le solde, une horloge indiquant le temps avant la prochaine rotation, et le bouton qui bascule vers l'autre interface.
- **Clic** : échange d'un lot, c'est-à-dire la plus petite quantité qui vaut au moins une pièce. **Shift-clic** : une stack à l'achat, tout l'inventaire à la vente.
- Avec un name tag ou une laisse en main, le clic garde son comportement vanilla.

## Le marché

- **Global :** prix et stock sont communs à tous les idiots. Seul le choix des articles proposés dépend du biome (du skin) de l'idiot.
- **Rotation :** toutes les 2 h. Un article proposé à la vente n'est jamais proposé à l'achat en même temps, sur tout le serveur.
- **Prix de rachat :** ce que l'idiot paie au joueur. Il baisse avec la quantité vendue au marché, sans jamais descendre sous le plancher de 1 pièce pour 64 unités. Le compteur de quantité vendue diminue de 2 % par rotation, ce qui permet aux prix de remonter.
- **Prix de vente :** ce que l'idiot facture au joueur. Il vaut toujours au moins le prix de rachat + le plancher.
- **Stock :** il est alimenté par les ventes des joueurs. La rotation de l'interface Achat favorise les articles en stock, et ceux en rupture restent visibles mais grisés.
- **Promotions :**
  - fréquence : sur toute série de 12 rotations consécutives, au moins 2 sont des rotations promo, soit environ 2 par 24 h ;
  - contenu : chaque biome a au moins un article de l'interface Achat en promo, à -25 % par défaut, sans jamais passer sous le prix de rachat + plancher ;
  - affichage : les articles en promo brillent, et l'horloge signale les promos en cours.

Les réglages sont dans `plugins/VanillaEconomy/config.yml` : markup, courbe de prix, decay, promos, filtres d'articles par biome, et chance qu'un bébé né d'une reproduction devienne idiot.

## Fichiers générés dans `plugins/VanillaEconomy/`

- `config.yml` : monnaie, `/pay`, paramètres du marché et des promos, filtres de pool par biome.
- `items.yml` : catalogue des articles (catégorie, tier, `base_price` pour `base_number` unités) et répartition des slots. Si la répartition change, la rotation est retirée automatiquement au démarrage.
- `economy.db` : base SQLite avec les tables `player_balance`, `coin_serial`, `market_item_state`, `market_rotation`, `villager_instance` et `meta`.

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

Le jar produit est `target/VanillaEconomy-1.0.0.jar`. Les tests unitaires (prix, rotation, promotions) sont lancés par `mvn package`.

Pour vérifier la compilation contre l'API Paper 26.3 :

```bash
JAVA_HOME=/chemin/vers/jdk-25 mvn package -Dpaper.version=26.3.build.142-beta
```
