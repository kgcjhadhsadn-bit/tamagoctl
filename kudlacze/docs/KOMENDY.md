# Komendy

Na Bedrocku komendy wpisuje się normalnie, ze znakiem `/`. Nazwy graczy Bedrock mają prefiks `.` (Floodgate).
Menu pluginu core (rangi, questy, tytuły, Kudłate Chaty) otwierają się na Bedrocku jako natywne formularze.

## Gracze — wszystkie serwery

| Komenda | Opis |
|---|---|
| `/serwery` | wybór trybu (Przewoźnik w lobby otwiera to samo menu) |
| `/menu` | menu główne (DeluxeMenus) |
| `/kc` (`/klaki`) | saldo Kłaków Czarodzieja (KC) |
| `/kc przelej <nick> <ilość>` | przelew KC |
| `/kc historia` | ostatnie transakcje KC |
| `/wyplac <ilość>` | wypłata KC jako przedmioty (do handlu), maks. 576 naraz |
| `/wplac [ilość\|wszystko]` | wpłata Kłaków z ekwipunku (podróbki i duplikaty są konfiskowane) |
| `/rangi` (`/czarodziej`) | menu Czarodzieja Kudłacza: rangi za KC |
| `/rangi kup <ranga>`, `/rangi lista` | zakup rangi (wymagana poprzednia ranga z drabinki) |
| `/ranking`, `/ranking archiwum [sezon]` | ranking bieżącego sezonu i archiwum |
| `/tytul` | wybór tytułu kosmetycznego (czat, TAB); `/tytul ustaw <id>`, `/tytul zdejmij` |
| `/regulamin` | regulamin |
| `/sklep` | informacja o sklepie (wyłączony — wszystko zdobywa się w grze) |
| `/discord link` | połączenie konta z Discordem (synchronizacja ról) |
| `/msg`, `/r`, `/ignore`, `/helpop` | EssentialsX |

## Survival

| Komenda | Opis |
|---|---|
| `/ps` | działki (ProtectionStones): postaw kamień działki, `/ps info`, `/ps add <nick>`, `/ps flag`, `/ps home`, `/ps list` |
| `/ps get dzialka` | zakup kamienia działki za pieniądze z gry |
| `/sethome [nazwa]`, `/home [nazwa]`, `/delhome` | domy (limit wg rangi: 3/5/10/15/20/25) |
| `/spawn`, `/tpa <nick>`, `/tpaccept`, `/tpdeny` | teleporty (opóźnienie 3 s; MVIP+ bez czekania) |
| `/bal`, `/pay <nick> <kwota>`, `/baltop` | pieniądze z gry (zł) |
| `/kit start`, `/kit vip` | zestawy |
| `/skarbnik` | codzienna nagroda (VIP+; NPC Skarbnik w strefie VIP) |
| `/edycja` | numer edycji, dzień, status Endu |
| `/chaty` (`/kustosz`) | konkurs Kudłate Chaty (NPC Kustosz na spawnie) |
| `/chaty zgloszenie [opis]` | zgłoszenie budowli (stojąc na własnej działce, w ostatnich 2 tygodniach edycji) |
| `/chaty wycofaj` | wycofanie zgłoszenia |

## Seasons

| Komenda | Opis |
|---|---|
| `/questy` (`/kwatermistrz`) | questy Kwatermistrza, wyzwania sezonu, sklep kryształów |
| `/sezon` | numer sezonu, Twoje punkty, miejsce, kryształy, czas gry |
| `/smok` | termin Smoka Kudłatego; gdy arena jest otwarta (15 min przed walką i w trakcie) — teleport na arenę |
| `/ps`, `/sethome`, `/home`, `/spawn` | jak na survivalu |

## Perki rang

| Komenda | Ranga |
|---|---|
| `/ec` (enderchest), `/wb` (warsztat) | SVIP+ |
| `/back` (także po śmierci) | UVIP+ |
| `/bruk` — bruk z ekwipunku na kamień | TYTAN |
| `/piec` — przetop przedmiotu w ręce | TYTAN (cooldown 10 s) |
| `/yt <link>` — ogłoszenie kanału | YouTuber (cooldown 30 min) |

## Lobby

| Komenda | Opis |
|---|---|
| `/parkour` | teleport na start parkouru |
| `/parkour top`, `/parkour wyjdz` | najlepsze czasy, przerwanie przejścia |

## Personel

| Komenda | Kto | Opis |
|---|---|---|
| `/warn`, `/mute`, `/kick` `<nick> [czas] <powód>` | pomocnik+ | kary LibertyBans (aliasy na `libertybans …`) |
| `/ban`, `/tempban`, `/unban`, `/unmute` | moderator+ | kary LibertyBans |
| `/history <nick>`, `/warns <nick>` | pomocnik+ | historia kar |
| `/co inspect`, `/co lookup` | pomocnik+ | CoreProtect |
| `/co rollback`, `/co restore` | moderator+ | CoreProtect |
| `/vanish`, `/invsee`, `/tp`, `/gamemode` | moderator+ | EssentialsX |
| `/chaty lista`, `/chaty tp <nick>`, `/chaty ocen <nick> <1-10>` | moderator+ | ocena Kudłatych Chat |
| `/chaty wyniki` | moderator+ | ogłoszenie wyników i nagrody (raz na edycję) |
| `/chaty okno otworz\|zamknij\|auto` | moderator+ | ręczne otwarcie/zamknięcie zgłoszeń |
| `/smok start`, `/smok stop` | viceadmin+ | ręczne przyzwanie / przerwanie walki ze smokiem |
| `/kc <nick>`, `/kc historia <nick>` | viceadmin+ | podgląd KC gracza |
| `/kc daj\|zabierz\|ustaw <nick> <ilość>` | admin | zmiana salda KC (logowana) |
| `/sezon reset` → `/sezon reset <kod>` | admin | ręczne zakończenie sezonu (kod ważny 60 s) |
| `/sezon punkty <nick> <±ilość>`, `/sezon przeladuj` | admin | korekta punktów, ponowne wczytanie `questy.yml` |
| `/edycja nowa` → `/edycja potwierdz <kod>` | admin | nowa edycja survivalu (kod ważny 60 s) |
| `/edycja end otworz\|zamknij` | admin | event otwarcia Endu |
| `/tytul nadaj\|odbierz <nick> <id>` | admin | ręczne tytuły |
| `/kadmin bootstrap` | admin | ponowne wykonanie `bootstrap.txt` (NPC, hologramy, granice, parkour) |
| `/kadmin moduly` | admin | lista włączonych modułów pluginu core |
| `spark`, `/tps`, `/mspt`, `/plugins`, `lp … info` | technik | diagnostyka (bez rozdawania przedmiotów i ekonomii) |

Z hosta:

| Skrypt | Opis |
|---|---|
| `./scripts/backup.sh` | kopia zapasowa (światy, pluginy, MariaDB), rotacja 14 dni |
| `./scripts/new-edition.sh` | nowa edycja survivalu z backupem i potwierdzeniem |
| `./scripts/check-logs.sh [usługa]` | przegląd logów |
| `./scripts/apply-permissions.sh` | ponowne zastosowanie uprawnień LuckPerms |
| `./scripts/build-resourcepack.sh` | budowa paczki zasobów + SHA1 |
| `docker compose exec <serwer> rcon-cli "<komenda>"` | komenda konsoli |
