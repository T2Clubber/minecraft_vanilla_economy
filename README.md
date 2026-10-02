# VanillaEconomy

Plugin Paper (Minecraft 26.2+, Java 25). Il ajoute une monnaie virtuelle et un marché global tenu par les idiots du village. Toute la logique tourne côté serveur : aucun mod n'est requis côté client.

## Build

```bash
JAVA_HOME=/chemin/vers/jdk-25 mvn package
```

Le jar produit est `target/VanillaEconomy-1.0.0.jar`, à placer dans `plugins/`. Le driver SQLite est téléchargé par Paper au premier démarrage, via la section `libraries` de `plugin.yml`.

## Fichiers générés dans `plugins/VanillaEconomy/`

- `config.yml` : monnaie, `/pay`, paramètres de pricing (markup, k, échelle, decay), filtres de pool par biome.
- `items.yml` : copie du fichier fourni, chargée telle quelle au démarrage.
- `economy.db` : base SQLite avec les tables `player_balance`, `coin_serial`, `market_item_state`, `market_rotation`, `villager_instance` et `meta`.

## Resource pack (texture de la pièce)

La pièce est une pépite d'or portant `custom_model_data = 1001`. Le pack (`resourcepack/`) l'affiche avec la texture `art/coin_model.jpeg` convertie en 48×48. Un joueur qui refuse le pack voit simplement une pépite d'or, et ses propres packs restent actifs.

```bash
python3 tools/convert_coin.py art/coin_model.jpeg resourcepack/assets/vanillaeco/textures/item/piece.png
```

```bash
python3 tools/build_pack.py
```

Le second script génère `dist/VanillaEconomy-pack.zip` et affiche son SHA-1. Après chaque modification du pack, il faut committer le zip puis mettre à jour `server.properties` :

```properties
resource-pack=https://raw.githubusercontent.com/T2Clubber/minecraft_vanilla_economy/main/dist/VanillaEconomy-pack.zip
resource-pack-sha1=<sha1 affiché par build_pack.py>
require-resource-pack=false
```

## Commandes

| Commande | Effet |
|---|---|
| `/solde` | Affiche le solde |
| `/pay <joueur> <montant>` | Transfert atomique (même monde, 10 blocs max) |
| `/money drop <montant>` | Convertit du solde en pièces physiques (nouvelle série signée) |
| `/money deposit` | Crédite les pièces tenues en main (vérification signature + registre anti-dupe) |
| `/marketadmin rotate` / `info <item>` | Admin : force une rotation / affiche l'état d'un item |

## Idiots marchands

- Clic droit : interface **ACHAT** (l'idiot vend). Sneak + clic droit : interface **VENTE** (l'idiot achète). Un bouton permet de passer de l'une à l'autre.
- Clic : 1 lot (la plus petite quantité qui vaut au moins 1 pièce). Shift-clic : une stack (achat) ou tout l'inventaire (vente).
- Avec un name tag ou une laisse en main, le clic garde son comportement vanilla.
