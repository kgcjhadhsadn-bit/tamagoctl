# Raport końcowy — sieć Kudłacze

Stan na koniec kamienia milowego M5. Sieć (Velocity + lobby + survival + seasons + MariaDB) startuje jednym
`docker compose up -d`. Wszystkie usługi osiągają stan `healthy`, a logi są czyste poza ostrzeżeniami opisanymi
w `scripts/log-allowlist.txt` i ograniczeniami środowiska testowego (niżej).

## Co zostało zbudowane

| Obszar | Zawartość |
|---|---|
| Infrastruktura | `docker-compose.yml` (limity pod VPS 16 GB), override do testów lokalnych, lockfile jarów z SHA256 (bez jarów w repo), `init-env.sh`, `download-plugins.sh`, `update-lock.sh`, `build-core.sh`, `check-logs.sh`, `apply-permissions.sh`, `backup.sh`, `new-edition.sh`, `build-resourcepack.sh`, `container/pre-start.sh` |
| Proxy | Velocity 4.2: online-mode, modern forwarding, Geyser + Floodgate (prefiks `.`), LuckPerms |
| Serwery | Paper 26.2 ×3: TAB, EssentialsX, WorldGuard/WorldEdit, ProtectionStones (21/31/41), FancyNpcs, DecentHolograms, DeluxeMenus, PlaceholderAPI, CoreProtect, LibertyBans, GrimAC, Chunky(+Border), DiscordSRV, ViaVersion, Vault |
| Uprawnienia | 13 grup LuckPerms, tory `rangi` i `personel`, prefiksy, meta (działki, domy, mnożnik XP), technik bez rozdawania i ekonomii |
| Plugin core | 22 moduły: waluta KC, rangi, śmierć, perki, czat, drwal, strefa VIP, lobby, bootstrap, sieć, info, tytuły, ranking, sezony, smok, edycje, anty-AFK, konkurs, parkour, sklep (wyłączony), logi kar, restart |
| Treści | polskie teksty (MiniMessage), regulamin, questy, rangi, tłumaczenie ProtectionStones, resource pack z modelem Kłaka |
| Dokumentacja | README, INSTALACJA_VPS (z checklistą), KOMENDY, UPRAWNIENIA, REGULAMIN, MONETYZACJA, ADMIN, EKONOMIA, WERSJE, ten raport |

## Co przetestowano

### Testy automatyczne

108 testów: JUnit 6, MockBukkit 26.2 i H2 w trybie MariaDB, na tych samych migracjach co produkcja.

- **Waluta:** saldo, przelewy, partie i podpisy przedmiotów, podróbki i duplikaty.
- **Rangi:** drabinka, przedłużanie, zwrot KC przy błędzie.
- **Nagrody i drwal:** codzienna nagroda, drwal (BFS), filtry czatu.
- **Sezony i smok:** harmonogramy ze zmianą czasu (DST), serwis sezonu (ranking, remisy, archiwum, idempotentny koniec sezonu), questy z limitami, nagrody smoka.
- **Reset i konkurs:** reset świata na plikach, tytuły, konkurs, walidacja sklepu (tylko kosmetyki).
- **Pozostałe moduły:** format logów kar, detektor AFK (kamera, autoklikacz), parkour, symulacja ekonomii (`EconomyTest` pilnuje zgodności z EKONOMIA.md), znaki nowej linii w komunikatach.

### Testy na żywo w Dockerze

Wszystkie 5 kontenerów było uruchomionych, a gracza udawał bot mineflayer:

| Scenariusz | Wynik |
|---|---|
| M3: spawn w lobby, selektor, `/kc`, zakup VIP i wymóg drabinki, wypłata i wpłata KC, Skarbnik z limitem dziennym, śmierć = kick → lobby, blokada 5 min | 13/13 |
| M4: `/questy` (menu, oddanie questu), `/sezon`, `/ranking`, `/tytul`, arena smoka, przyzwanie kryształami, śmierć na arenie bez kicka z odrodzeniem na arenie, pokonanie smoka, nagrody top 5, tytuł na czacie | 18/18 |
| M5: parkour (start, spadek, punkt kontrolny, meta, rekord, top), `/sklep`, Kudłate Chaty (działka PS, zgłoszenie, duplikat, ocena, wyniki, 60 KC + tytuł, nagrody raz na edycję), logi kar LibertyBans | 18/18 |
| Reset sezonu (`/sezon reset` z kodem) | archiwum top 10, 50/30/20 KC + tytuły, sezon +1, nowy świat, **nowy seed**, nowa arena, czyszczenie CoreProtect/WG/Essentials |
| Nowa edycja (`/edycja nowa` + kod, potem `scripts/new-edition.sh`) | wygaszenie VIP, archiwum świata, reset domów i pieniędzy, **nowy seed**, nowy spawn i NPC, edycje 1→2→3 |
| Anty-AFK (skrócone czasy) | AFK po 1 min, ostrzeżenie (tytuł + czat) po 1,5 min, kick po 2 min |
| Restart dzienny (godzina ustawiona na +3 min) | ostrzeżenie 1 min przed, wyłączenie o czasie, Docker podniósł kontener |
| Backup | światy + pluginy + dump MariaDB (78 tabel), sumy SHA256, `save-on` przywrócone, rotacja (kopia sprzed 20 dni usunięta, z 5 dni zostaje) |
| Hologram rankingu i placeholdery w lobby | `%kudlacze_top_…%`, `sezon_koniec`, `smok_za` działają przez PlaceholderAPI |

Poprawki wynikające z testów na żywo:

1. **Seed i dane świata.** Paper 26.2 wczytuje `level.dat` i seed przed `onLoad()`. Reset świata przeniesiono do
   entrypointu kontenera (`pre-start.sh`).
2. **Pierwsze wejście na arenę.** Walka ze smokiem w 26.2 sama tworzy smoka, jeśli przy tym wejściu nie ma
   aktywnego portalu. Arena dostaje aktywny portal, a „zabłąkane” smoki są usuwane przed walką.
3. **Obrażenia smoka.** Trafienia w ciało są dzielone przez 4. Test uwzględnia to przy zadawaniu obrażeń.
4. **Chunki bez graczy.** W 26.x chunki spawnu nie są stale załadowane. Dodano dyrektywę `@zaladuj` w bootstrapie.
5. **Pliki tekstów.** `messages_pl.yml` / `rangi.yml` / `questy.yml` nie są już kopiowane do folderu pluginu,
   bo nowe wersje z jara by nie wchodziły.
6. **Znaki nowej linii.** Komunikaty kicka pokazywały dosłowne `\n`; teraz używają `<newline>`.
7. **Ocena w konkursie.** Nieoceniona budowla dostawała ocenę 0 zamiast braku oceny (błąd `wasNull()`).
8. **Parkour.** Zmieniono zachowanie płytki startu i wykrywanie spadku.

## Działania ręczne przed startem produkcji

1. **VPS i zapora.**
   - Ubuntu/Debian, strefa `Europe/Warsaw`, ufw: 22/tcp, 25565/tcp, 19132/udp (patrz [INSTALACJA_VPS.md](INSTALACJA_VPS.md)).
2. **`.env`.**
   - Uruchom `./scripts/init-env.sh`.
   - Ustaw `SERVER_DOMAIN` i **`COMPOSE_FILE=docker-compose.yml`**.
   - `DISCORD_INVITE`.
3. **DNS.**
   - Rekord `A` (i opcjonalnie `SRV`) domeny na IP VPS.
4. **Discord.**
   - Bot z intencjami Members i Message Content, token w `.env`.
   - ID kanałów: czat-survival, czat-seasons, logi-kar, ranking, smok, konsola.
   - ID ról VIP…TYTAN, DRWAL, YouTuber i personelu.
5. **Resource pack.**
   - `./scripts/build-resourcepack.sh`, hosting HTTPS, `RESOURCE_PACK_URL` i `RESOURCE_PACK_SHA1` w `.env`.
6. **Administrator.**
   - Uruchom `./scripts/apply-permissions.sh`.
   - Potem `lp user <nick> parent set admin`.
7. **Kopie zapasowe.**
   - Cron `0 4 * * *` dla `scripts/backup.sh` (jako root albo UID 1000).
   - Wysyłka kopii poza VPS.
8. **Build pluginu.**
   - JDK 25 na serwerze albo gotowy jar z innej maszyny.

## Znane ograniczenia

- **Środowisko testowe, nie produkcja.** Testy na żywo wykonano w piaskownicy bez prawdziwego klienta Minecraft.
  - Bot mineflayer łączy się jako konto offline przez **nadpisanie tylko w piaskownicy** (proxy `online-mode=false`, poza repozytorium). W repo proxy działa w `online-mode=true`.
  - Z tego samego powodu w logach piaskownicy są błędy Yggdrasil i sesji Mojang (hosty spoza listy dozwolonych) oraz ViaBackwards, doinstalowany tylko dla bota.
  - Bot testowy dostał `grim.exempt`, bo jego ruch nie przechodzi symulacji GrimAC.
- **Bedrock** nie był testowany prawdziwym klientem. Geyser i Floodgate startują poprawnie. Menu pluginu core
  mają ścieżkę formularzy Floodgate (SimpleForm), ale nie sprawdzono jej na urządzeniu.
- **Discord** nie był połączony z prawdziwym botem (brak tokenu). Sprawdzono składnię `discordsrv bcast` w źródłach
  DiscordSRV, odczyt kar z bazy (moduł `logi-kar`), wczytanie kanałów i ról z `.env` oraz komplet konfiguracji.
  Wysyłki na Discord nie przetestowano.
- **Resource pack.** Format sprawdzono w kodzie klienta 26.2 (`pack_format` 88, `custom_model_data` w
  `minecraft:select`), ale wyglądu w kliencie nie obejrzano.
- **Wydajność przy 100 graczach** nie była mierzona. Limity pamięci i dystanse widzenia są ustawione wg planu.
  Na produkcji warto obserwować `/spark tps` i `/spark profiler`.
- **Pierwsza pregeneracja** survivalu (5000×5000 + Nether) trwa kilka godzin. Serwer działa w tym czasie
  normalnie. Po każdej edycji i każdym sezonie pregeneracja rusza od nowa.
- **Wersje pluginów.**
  - ProtectionStones 2.10.6 jest opisany dla 1.21.10. Na 26.2 sprawdzono tworzenie działek i właścicieli; wynajmu, podatków i łączenia działek nie testowano.
  - EssentialsX to build deweloperski, a GrimAC to alpha, bo stabilnych wydań dla 26.x jeszcze nie ma. Szczegóły w [WERSJE.md](WERSJE.md).
- **Smok.** Bloki postawione na arenie w trakcie walki są sprzątane z pamięci. Restart serwera w trakcie walki
  zostawi je na arenie; zniknie ona i tak przy resecie sezonu.
- **End na survivalu.** Automatyczne otwarcie po 7 dniach i blokada portalu opierają się na standardowych
  zdarzeniach Bukkit (`PlayerPortalEvent`, `EntityPortalEvent`). Ścieżki portalu nie testowano botem.
- **Edycje survivalu** uruchamia administrator (`/edycja nowa` albo `scripts/new-edition.sh`). Okno Kudłatych Chat
  liczy się od planowanej długości edycji (90 dni). Przy innej długości otwórz je ręcznie: `/chaty okno otworz`.
- **Archiwa światów** (`data/survival/archiwum/`) rosną z każdą edycją. Trzeba je okresowo przenosić poza VPS.

## Historia zmian (gałąź `claude/kind-dijkstra-r7j7uj`)

| Commit | Kamień milowy |
|---|---|
| M1 | szkielet sieci: Velocity + 3× Paper + MariaDB, lockfile, LuckPerms |
| M2 | pluginy bazowe, działki, ekonomia, uprawnienia, lobby z Przewoźnikiem |
| M3 | plugin core: KC, rangi, śmierć = kick, strefa VIP, perki, drwal, czat |
| M4 | Seasons: questy, ranking, Smok Kudłaty, reset sezonu, edycje survivalu |
| M5 | anty-AFK, Kudłate Chaty, restart, parkour, sklep (wyłączony), logi kar, DiscordSRV, backup, resource pack, tłumaczenie PS, dokumentacja |
