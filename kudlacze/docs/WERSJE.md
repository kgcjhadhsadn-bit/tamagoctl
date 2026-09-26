# Wersje i uzasadnienia

Dokładne wersje, URL-e i sumy SHA256 są w [`plugins.lock.yml`](../plugins.lock.yml). Nowsze wersje sprawdza
`./scripts/update-lock.sh`, a `--apply` zapisuje je do locka. Po zmianie uruchom testy, start i `check-logs.sh`.

## Platforma

| Składnik | Wersja | Dlaczego |
|---|---|---|
| Minecraft / Paper | **26.2** (build 129, kanał STABLE) | Po 1.21.11 Minecraft przeszedł na numerację 26.x. 26.2 to aktualne wydanie stabilne, a ekosystem pluginów celuje w nie. 26.3 było w chwili budowy eksperymentalne. |
| Velocity | **4.2.0** (build 30, STABLE) | wydanie stabilne; resolver pomija buildy SNAPSHOT |
| Java | **25** (Temurin) | wymagana przez Paper 26.x; obrazy `itzg/*:2026.9.2-java25`, toolchain Gradle 25 |
| Obrazy Dockera | `itzg/minecraft-server:2026.9.2-java25`, `itzg/mc-proxy:2026.9.2-java25`, `mariadb:12.3.3` | wersje przypięte, bez `latest` |
| MariaDB | 12.3.3 | wspólna baza dla LuckPerms, LibertyBans, CoreProtect, DiscordSRV i pluginu core |

Uwaga do 26.x: wszystkie wymiary świata są w jednym folderze (`world/dimensions/minecraft/…`), a dane graczy
w `world/players/`. Dlatego reset edycji lub sezonu przenosi albo usuwa cały folder `world`. Paper wczytuje
`level.dat` i seed (`world_gen_settings.dat`) **przed** `onLoad()` pluginów. Świat usuwa więc
`scripts/container/pre-start.sh` przed startem Javy. Ta zależność jest sprawdzona na żywo: nowy seed po resecie.

## Pluginy

| Plugin | Wersja | Źródło | Uwagi |
|---|---|---|---|
| LuckPerms (Paper + Velocity) | 5.5.71 | Modrinth | MariaDB + `messaging-service: sql` |
| Geyser (Velocity) | 2.11.3 (b1247) | download.geysermc.org | Bedrock przez proxy |
| Floodgate (Velocity + Paper) | 2.2.5 (b141) | download.geysermc.org | wspólny klucz `secrets/floodgate-key.pem`; prefiks Bedrock `.`; API formularzy w core |
| ViaVersion | 5.12.0 | Modrinth | **na backendach**, nie na proxy: GrimAC nie wspiera ViaVersion na Velocity |
| ViaBackwards | — | — | **pominięty**: GrimAC ostrzega o niewspieranej konfiguracji na 1.21.2+ (pojazdy starszych klientów) |
| EssentialsX + EssentialsXSpawn | 2.22.1-dev+24 (Jenkins #1829) | ci.ender.zone | wydanie 2.22.0 nie wspiera 26.2; oficjalny build deweloperski z CI projektu |
| VaultUnlocked | 2.20.3 | Modrinth | API ekonomii (Vault 1 + 2) |
| PlaceholderAPI | 2.12.3 | Hangar | |
| WorldEdit / WorldGuard | 7.4.5 / 7.0.19 | Modrinth | |
| ProtectionStones | 2.10.6 | Modrinth (tag `1.21.10`) | najnowsze wydanie; opisane dla 1.21.10, na 26.2 przetestowane na żywo (tworzenie działki, sprawdzanie właściciela) |
| FancyNpcs | 2.12.1 | Modrinth | NPC: Przewoźnik, Czarodziej Kudłacz, Skarbnik, Kwatermistrz, Kustosz |
| DecentHolograms | 2.10.1 | Modrinth | hologramy z placeholderami (ranking, parkour); ostrzeżenie NBT-API o wersji serwera jest nieszkodliwe |
| TAB | 6.2.0 | Modrinth | |
| DeluxeMenus | 1.14.1 | Modrinth | selektor serwerów i `/menu`; ostrzeżenie „NMS hook” dotyczy tylko opcji `nbt_*`, których nie używamy |
| GrimAC | 2.3.74 | Modrinth, **kanał alpha** | dla 26.x istnieją tylko buildy alpha |
| LibertyBans | 1.1.4 | Modrinth | open source, MariaDB; aliasy `/ban` itd. w `commands.yml` (EssentialsX rejestruje je wcześniej) |
| CoreProtect CE | 24.1 | Modrinth | tabele `co_survival_*` / `co_seasons_*`, czyszczone przy resecie |
| Chunky + ChunkyBorder | 1.5.3 / 1.2.23 | Modrinth | pregeneracja i granice świata |
| DiscordSRV | 1.30.5 | Modrinth | czat, role, ogłoszenia (`discordsrv bcast`), konta w MariaDB |
| spark | wbudowany w Paper | — | nie ma pobieralnej wersji spark dla Velocity na Modrinth; na backendach działa `/spark` z Paper |

## Plugin core (build)

| Narzędzie | Wersja |
|---|---|
| Gradle (wrapper) | 9.8 |
| Shadow (com.gradleup.shadow) | 9.6.1 — relokacja HikariCP i sterownika MariaDB do `pl.kudlacze.core.lib` |
| paper-api | 26.2.build.129-stable |
| HikariCP / mariadb-java-client | 7.1.0 / 3.5.10 |
| JUnit / MockBukkit / H2 | 6.1.3 / mockbukkit-v26.2 4.116.1 / 2.5.250 (tryb MariaDB) |
| LuckPerms API, WorldGuard, WorldEdit, PlaceholderAPI, Floodgate API, VaultAPI | compileOnly |

## Resource pack

Format paczki zasobów 26.2: `resource_major = 88` (z `version.json` serwera). `pack.mcmeta` używa
`min_format`/`max_format`. Model przedmiotu (`assets/minecraft/items/amethyst_shard.json`) wybiera
`minecraft:select` po `minecraft:custom_model_data` (string `kudlacze:klak_czarodzieja`). Format sprawdzono
w kodzie klienta 26.2 (`CustomModelDataProperty`, pole `index`).
