# Kudłacze — sieć serwerów Minecraft

> **Serwer z kudłami! SURVIVAL + DZIAŁKI**

Kudłacze to polska sieć w stylu klasycznych serwerów „Survival + Działki”. Składa się z trzech serwerów:

- **lobby** z wyborem trybu;
- **survival** z działkami i edycjami co ok. 3 miesiące;
- **seasons** z comiesięcznym resetem, questami, rankingiem i cotygodniowym Smokiem Kudłatym.

Całość działa za proxy Velocity (Java + Bedrock przez Geyser), w Dockerze, na Paper 26.2 i Javie 25.

Własny plugin **KudlaczeCore** (`plugins-src/core`) zapewnia:
- Kłaki Czarodzieja (KC) — walutę na koncie i jako przedmiot;
- rangi VIP → SVIP → MVIP → UVIP → TYTAN oraz DRWAL, wymieniane za KC u Czarodzieja Kudłacza;
- zasadę „śmierć = kick”;
- strefę VIP ze Skarbnikiem i expiarką;
- perki, drwala, questy, ranking, smoka, edycje, anty-AFK, konkurs Kudłate Chaty, parkour i tytuły.

**Wszystko, co daje przewagę, zdobywa się wyłącznie w grze.** Serwer nie sprzedaje KC ani rang. Szczegóły w [docs/MONETYZACJA.md](docs/MONETYZACJA.md).

## Szybki start (lokalnie)

Wymagania: Docker z Compose v2, Java 25 (tylko do budowy pluginu core; alternatywnie `gradle` w kontenerze), Python 3, `curl`.

```bash
cd kudlacze
./scripts/init-env.sh           # tworzy .env z losowymi hasłami/sekretami + klucz Floodgate
./scripts/download-plugins.sh   # Paper, Velocity i pluginy z plugins.lock.yml (weryfikacja SHA256)
./scripts/build-core.sh         # plugin KudlaczeCore (testy + shadow jar)
docker compose up -d            # mariadb, velocity, lobby, survival, seasons
./scripts/apply-permissions.sh  # grupy i uprawnienia LuckPerms (raz, po pierwszym starcie)
./scripts/check-logs.sh         # przegląd logów: ERROR/WARN poza listą znanych
```

Adres: `localhost:25565` (Java) i `localhost:19132/udp` (Bedrock). Lokalnie `docker compose` wczytuje
`docker-compose.override.yml` z mniejszymi limitami pamięci. Na VPS wyłącza się to w `.env` przez `COMPOSE_FILE=docker-compose.yml`.

## Architektura

```
                ┌──────────── Velocity 4.2 (25565/tcp, 19132/udp Geyser) ─────────────┐
 gracze Java ──►│  online-mode=true, modern forwarding, LuckPerms, Geyser+Floodgate   │
 gracze Bedrock►│  try: lobby                                                          │
                └───────┬───────────────────────┬───────────────────────┬─────────────┘
                        ▼                       ▼                       ▼
                  lobby (2G)             survival (6G)            seasons (4G)
            Przewoźnik, parkour,     działki PS, edycje,      questy, ranking, smok,
            hologram rankingu        Kustosz, Skarbnik        reset 1. dnia miesiąca
                        └───────────────┬───────┴───────────────┬───────┘
                                        ▼                       ▼
                               MariaDB 12.3 (1.5G): LuckPerms, LibertyBans, CoreProtect,
                               KudlaczeCore (KC, rangi, sezony, konkurs…), DiscordSRV (konta)
```

| Katalog | Zawartość |
|---|---|
| `proxy/velocity/` | `velocity.toml`, konfiguracja pluginów proxy |
| `servers/_common/`, `servers/<serwer>/` | `server.properties`, `bukkit.yml`, `spigot.yml`, `config/paper-*.yml`, configi pluginów (synchronizowane do `data/` przy starcie, `${CFG_*}` z `.env`) |
| `plugins-src/core/` | plugin KudlaczeCore (Gradle Kotlin DSL, testy JUnit + MockBukkit) |
| `plugins.lock.yml` | wersje, URL-e i SHA256 wszystkich jarów (bez jarów w repo) |
| `scripts/` | pobieranie, budowa, uprawnienia, logi, backup, nowa edycja, resource pack, `container/pre-start.sh` |
| `permissions/luckperms.txt` | grupy, dziedziczenie, prefiksy, meta i uprawnienia |
| `resourcepack/` | paczka zasobów (model Kłaka Czarodzieja) |
| `docs/` | dokumentacja |

## Dokumentacja

- [INSTALACJA_VPS.md](docs/INSTALACJA_VPS.md): instalacja na VPS 8 vCPU / 16 GB, ufw, cron, DNS, Discord i checklista wdrożenia.
- [KOMENDY.md](docs/KOMENDY.md): komendy graczy i personelu.
- [UPRAWNIENIA.md](docs/UPRAWNIENIA.md): grupy LuckPerms, rangi i uprawnienia.
- [REGULAMIN.md](docs/REGULAMIN.md): regulamin serwera.
- [MONETYZACJA.md](docs/MONETYZACJA.md): co jest gameplay, a co kosmetyką; zgodność z EULA.
- [ADMIN.md](docs/ADMIN.md): obsługa codzienna, edycje, sezony, smok, konkurs, kopie, awarie.
- [EKONOMIA.md](docs/EKONOMIA.md): balans KC (ok. 20 KC tygodniowo), ceny, nagrody.
- [WERSJE.md](docs/WERSJE.md): wybór wersji i uzasadnienia (Paper 26.2, EssentialsX dev, Grim alpha…).
- [RAPORT.md](docs/RAPORT.md): raport końcowy z tego, co działa i co przetestowano, z ograniczeniami.

## Testy

```bash
cd plugins-src/core && ./gradlew test     # 108 testów: JUnit 6 + MockBukkit 26.2 + H2 (tryb MariaDB)
```
