# Podręcznik administratora

## Codzienna obsługa

```bash
cd /opt/kudlacze
docker compose ps                              # stan usług (healthy)
./scripts/check-logs.sh                        # ERROR/WARN poza listą znanych (scripts/log-allowlist.txt)
docker compose logs -f --tail 100 survival     # log na żywo
docker compose exec survival rcon-cli          # konsola interaktywna (wyjście: Ctrl+D)
docker compose exec survival rcon-cli "tps"    # jedna komenda
```

Cykl dnia (strefa Europe/Warsaw):

| Godzina | Co | Kto |
|---|---|---|
| 04:00 | kopia zapasowa (światy, pluginy, MariaDB), rotacja 14 dni | cron hosta, `scripts/backup.sh` |
| 05:00 | restart wszystkich backendów (ostrzeżenia 15 / 5 / 1 min) | moduł `restart` pluginu core; Docker podnosi kontenery |
| niedziela 19:30 | zapowiedź Smoka Kudłatego (czat + Discord #smok) | moduł `smok` |
| niedziela 19:45–20:00 | arena otwarta (`/smok`), walka o 20:00, limit 30 min | moduł `smok` |
| 1. dzień miesiąca 12:00 | koniec sezonu: archiwum, nagrody top 3, nowy świat Seasons | moduł `sezony` |

## Konfiguracja pluginu core

Pliki w repozytorium (źródło prawdy), synchronizowane do `data/<serwer>/plugins/KudlaczeCore/` przy starcie:

| Plik | Co ustawia |
|---|---|
| `servers/<serwer>/plugins/KudlaczeCore/config.yml` | włączone moduły (`moduly:`), tryb śmierci, AFK, parkour, sezon, smok, edycja… Brakujące klucze biorą się z `config.yml` w jarze. |
| `servers/<serwer>/plugins/KudlaczeCore/bootstrap.txt` | komendy wykonywane po zmianie pliku lub nowym świecie: NPC, hologramy, granice, pregeneracja, trasa parkouru. `@zaladuj x1 z1 x2 z2` ładuje chunki dla `setblock`. |
| `plugins-src/core/src/main/resources/messages_pl.yml`, `rangi.yml`, `questy.yml` | teksty, rangi, questy (w jarze). Własny plik o tej nazwie w folderze pluginu **nadpisuje** wersję z jara: `messages_pl.yml` klucz po kluczu, `rangi.yml` i `questy.yml` w całości. |

Moduły na serwerach:

| Moduł | lobby | survival | seasons |
|---|---|---|---|
| waluta, rangi, czat, siec, info, tytuly, ranking, afk, sklep, restart, bootstrap | ✔ | ✔ | ✔ |
| lobby, parkour | ✔ | | |
| smierc, perki, drwal, strefa-vip | | ✔ | ✔ |
| edycje, konkurs, logi-kar | | ✔ | |
| sezony, smok | | | ✔ |

Po zmianie configu: `docker compose restart <serwer>`. Po zmianie `bootstrap.txt` wykona się on sam przy starcie;
ręcznie uruchamia go `/kadmin bootstrap`.

## Nowa edycja survivalu (co ok. 3 miesiące)

```bash
./scripts/new-edition.sh     # backup → /edycja nowa → potwierdzenie kodem → restart
```

Albo w grze: `/edycja nowa`, a potem `/edycja potwierdz <kod>` w ciągu 60 s. Co się dzieje:

1. Numer edycji rośnie (tabela `edycje`, `core_kv survival.edycja`), End zostaje zamknięty.
2. Rangi gameplay (VIP…TYTAN, DRWAL) są odbierane wszystkim. Personel, YouTuber, tytuły i saldo KC zostają.
3. Logi bloków CoreProtect starego świata są czyszczone, żeby rollback w nowym świecie nie przywrócił starych bloków.
4. Gracze trafiają do lobby, a survival się wyłącza.
5. Przed startem Javy `scripts/container/pre-start.sh` przenosi świat do `data/survival/archiwum/edycja-N-<data>/`.
6. Plugin czyści regiony WorldGuard (działki), zadania Chunky oraz domy i pieniądze EssentialsX.
7. Serwer generuje nowy świat z **losowym seedem**, buduje spawn, NPC i hologramy, ustawia granice 5000×5000 i uruchamia pregenerację.

End otwiera się automatycznie 7 dni po starcie edycji o 20:00 (`edycja.end.*`). Można go też otworzyć eventem:
`/edycja end otworz`.

Kudłate Chaty: zgłoszenia przyjmowane są w ostatnich 14 dniach planowanej edycji (`edycja.dlugosc-dni: 90`).
Na koniec edycji: `/chaty lista`, `/chaty tp <nick>`, `/chaty ocen <nick> <1-10>`, `/chaty wyniki`. Nagrody
dostaje się raz na edycję. Gdy edycja trwa inaczej niż 90 dni, zgłoszenia otwiera ręcznie `/chaty okno otworz`.

Archiwa starych światów zajmują miejsce. Po kilku edycjach przenieś je poza VPS (`data/survival/archiwum/`).

## Sezon Seasons (co miesiąc)

Działa automatycznie 1. dnia miesiąca o 12:00. Jeśli serwer był wtedy wyłączony, reset wykona się minutę po
starcie („zaległy reset”). Przebieg:

1. Top 10 trafia do `sezon_archiwum` (`/ranking archiwum <sezon>`).
2. Top 3 dostaje 50/30/20 KC i tytuły Mistrz Sezonu, Wicemistrz Sezonu i Brąz Sezonu.
3. Ogłoszenie idzie na czat i Discord #ranking, a potem nowy świat (usunięty, bez archiwum) z nową areną smoka.

Ręcznie: `/sezon reset`, a potem `/sezon reset <kod>`. Korekta punktów: `/sezon punkty <nick> <±ilość>`.
Questy i wyzwania są w `questy.yml`; po zmianie w folderze pluginu użyj `/sezon przeladuj`.

## Smok Kudłaty

- Arena to osobny świat `seasons_boss` (THE_END, granica 400 bloków, keep_inventory, bez mobów).
- Przyzwanie przez sekwencję kryształów (jak w vanilli). Smok ma 400 HP (`smok.zdrowie`).
- Top 5 wg obrażeń dostaje 100/80/60/40/20 punktów sezonu, 10/8/6/4/2 KC i tytuł Smokobójca. Minimum to 10 obrażeń.
- Bez niszczenia terenu (bloki postawione w walce znikają minutę po końcu), bez bramek Endu, bez PvP (`smok.pvp`).
- Śmierć na arenie nie wyrzuca, a gracz odradza się na arenie.
- Ręcznie: `/smok start` (potrzebny co najmniej 1 gracz na arenie) i `/smok stop` (bez nagród).

## Kary i moderacja

- LibertyBans: `/warn`, `/mute`, `/kick`, `/ban`, `/tempban` (aliasy w `commands.yml`), historia `/history`.
  Kary obowiązują w całej sieci (wspólna baza) i trafiają na Discord #logi-kar.
- Grief: `/co inspect`, `/co lookup u:<nick> t:3d r:20`, `/co rollback u:<nick> t:3d r:30` (moderator+).
- Podróbki i duplikaty KC są konfiskowane automatycznie przy `/wplac`, a personel online dostaje alert. Dziennik
  transakcji: tabela `kc_transakcje` albo `/kc historia <nick>`.
- Anty-AFK: po 5 min bezczynności gracz jest AFK i nie dostaje punktów za czas gry. Po 15 min dostaje kick
  (w lobby po 20). Stały rytm ataków (makro) jest zgłaszany personelowi.
- GrimAC: alerty dla pomocnika+ (`grim.alerts`).

## Awarie

| Objaw | Co zrobić |
|---|---|
| kontener `unhealthy` / restartuje się w pętli | `docker compose logs --tail 200 <serwer>`; brak pamięci → sprawdź `COMPOSE_FILE` i limity |
| „Plugin core zostaje wyłączony” | problem z bazą: `docker compose ps mariadb`, hasła w `.env` |
| gracze Bedrock nie wchodzą | UDP 19132 w ufw i u dostawcy VPS; `docker compose logs velocity \| grep -i geyser` |
| NPC lub hologramy zniknęły | `/kadmin bootstrap` na danym serwerze |
| reset świata się nie wykonał | czy w entrypoincie jest `pre-start.sh` (`docker compose config \| grep pre-start`); znacznik `data/<serwer>/plugins/KudlaczeCore/reset-swiata.properties` |
| przywrócenie z kopii | [INSTALACJA_VPS.md → Cron](INSTALACJA_VPS.md#5-cron-backup-0400-i-restart-0500) |

## Znane ostrzeżenia w logach

Opisane w `scripts/log-allowlist.txt`. Są nieszkodliwe:
- NBT-API w DecentHolograms nie zna wersji 26.2;
- DeluxeMenus: brak „NMS hook” dla opcji `nbt_*`;
- LibertyBans: `/ban` zajęty przez EssentialsX (rozwiązane aliasami);
- DiscordSRV bez tokenu;
- MineSkin bez klucza API (FancyNpcs);
- chwilowy brak profilu z API Mojang.
