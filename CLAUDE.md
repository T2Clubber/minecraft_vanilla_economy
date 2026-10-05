# VanillaEconomy — notes pour Claude

Plugin Paper (Bukkit, `plugin.yml`) pour un serveur Minecraft **26.3** français, 100 % vanilla côté client. Le serveur de production est hébergé chez Nitroserv (build Paper 26.3 n°32, chargement « legacy » des plugins). Le README décrit les fonctionnalités, ce fichier décrit comment travailler sur le projet.

## Build et tests

- Il faut **Java 25**, que la machine n'a pas installé. Utiliser un JDK 25 Temurin téléchargé dans le scratchpad, puis `JAVA_HOME=<jdk25> mvn -B clean package`. Ne jamais l'installer sur le système.
- Le jar produit est `target/VanillaEconomy-1.0.0.jar`. Les tests JUnit portent sur la logique pure (prix, rotation, promotions, règles des cités) et doivent rester verts sans warning de compilation.
- Pour tester en conditions réelles : serveur Paper 26.3 lancé en arrière-plan dans le scratchpad (`online-mode=false`, EULA acceptée par l'utilisateur), commandes envoyées via `tail -f console.in | java -jar paper.jar`, logs lus dans `server.log`.
  - Sans joueur connecté, les entités ne sont plus tickées après quelques secondes.
  - En 26.3, `white-list=true` est la valeur par défaut d'un nouveau serveur.
- Vérifier le bytecode et l'API Paper avec `javap` sur le jar `paper-api` plutôt que de supposer : l'API 26.x diffère des anciennes versions (`Villager.Type` en registre, `PrepareResultEvent`, etc.).

## Conventions du code

- **Textes joueurs en français**, au format MiniMessage via `util.Messages`. Les textes des cités sont dans `messages.yml`, lus par `util.MessageConfig` ; les variables `{x}` y sont injectées en placeholders non parsés.
- **Argent** : tout débit ou crédit passe par `CurrencyManager.debit/credit(Connection, …)`, à l'intérieur d'un `db.transaction(...)`. La mémoire n'est mise à jour qu'après le commit.
- **Performance** : aucune requête SQL dans les handlers chauds (déplacement, protection). Les cités utilisent l'index spatial en mémoire (`SpatialIndex`).
- **Threads** : SQLite n'est utilisé que depuis le thread principal. Les GUI ouvrent et ferment les inventaires au tick suivant, jamais dans l'`InventoryClickEvent` lui-même.
- **Heures** : toujours `util.ServerTime.zone()` (réglage `timezone`, Europe/Paris par défaut), jamais le fuseau de la JVM (l'hébergeur est en UTC).
- **Données par monde** : la base est dans `<level-name>/vanillaeconomy/economy.db`, la racine de sauvegarde étant le parent de `dimensions/`. Les configs (`config.yml`, `items.yml`, `cities.yml`, `messages.yml`) sont globales.
- **`items.yml`** a été fourni et équilibré par l'utilisateur : ne pas le régénérer. On ajoute seulement des entrées quand c'est demandé.
- **Contraintes de prix** : prix de vente ≥ prix d'achat après chaque recalcul, promos comprises ; plancher de 1/64 par unité ; aucun article à la fois en VENTE et en ACHAT sur tout le serveur.

## Ressources hors code

- `resourcepack/` contient le pack de la pièce (custom_model_data 1001 pour la pièce, 1002 et 1003 pour les icônes A et V). `tools/build_pack.py` régénère `dist/VanillaEconomy-pack.zip` et affiche son SHA-1, qui doit être reporté dans `server.properties`.
- `art/` contient l'image source de la pièce et `server-icon.png` (64×64).
- **`/discord/`** contient les scripts de configuration du serveur Discord. Ce dossier est **privé** : ignoré par git, à ne jamais committer. Le token est saisi par l'utilisateur avec `read -rs` dans son terminal : ne jamais le demander ni le manipuler. Les webhooks (`discord.webhook_url`, `discord.logs_webhook_url`) ne se configurent que dans le `config.yml` du serveur.

## Git

- Dépôt public : `T2Clubber/minecraft_vanilla_economy`, branche `main`. Les commits sont faits sous le nom `T2Clubber`, messages en français, avec la ligne `Co-Authored-By` de Claude.
- L'utilisateur valide le push des changements de code du plugin ; ce qui touche Discord reste local.
