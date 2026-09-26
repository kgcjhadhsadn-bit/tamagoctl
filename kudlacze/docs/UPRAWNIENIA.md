# Uprawnienia

Pełna lista komend LuckPerms jest w [`permissions/luckperms.txt`](../permissions/luckperms.txt). Stosuje ją
`./scripts/apply-permissions.sh` (idempotentnie). LuckPerms trzyma dane w MariaDB i synchronizuje je między proxy
i backendami (`messaging-service: sql`).

## Grupy i dziedziczenie

```
default ─┬─ vip ── svip ── mvip ── uvip ── tytan        (tor „rangi”, za KC, 30 dni lub do nowej edycji)
         ├─ drwal                                        (osobno, za KC, 30 dni)
         ├─ youtuber                                     (nadawana ręcznie)
         └─ pomocnik ── moderator ─┬─ viceadmin ── admin (tor „personel”)
                                   └─ technik
```

| Grupa | Waga | Jak zdobyć |
|---|---|---|
| default | 1 | każdy gracz |
| drwal | 5 | 40 KC u Czarodzieja Kudłacza |
| vip / svip / mvip / uvip / tytan | 10–50 | 80 KC każda, wymagana poprzednia z drabinki |
| youtuber | 60 | administracja (kanał z publicznością, bez przewag w grze) |
| pomocnik → admin | 100–400 | rekrutacja |
| technik | 450 | obsługa techniczna: konsola, spark, podgląd; **bez** `give`, `item`, `more`, `eco` i `kc admin` |

Rangi gameplay są nadawane przez plugin core jako tymczasowe węzły LuckPerms (30 dni). Kolejny zakup przedłuża
ważność. `/edycja nowa` odbiera je wszystkim, jeśli `edycja.wygas-rangi: true`. Grupy personelu, YouTuber i tytuły
zostają.

## Co daje ranga

| | default | VIP | SVIP | MVIP | UVIP | TYTAN |
|---|---|---|---|---|---|---|
| Działki (limit) | 5 | 15 | 30 | 45 | 60 | 75 |
| Rozmiar działki | 21×21 | 31×31 | 31×31 | 31×31 | 31×31 | 41×41 |
| Domy | 3 | 5 | 10 | 15 | 20 | 25 |
| Strefa VIP, expiarka, Skarbnik, `/kit vip`, rezerwacja slotu | | ✔ | ✔ | ✔ | ✔ | ✔ |
| `/ec`, `/wb`, XP ×1,5 | | | ✔ | ✔ | ✔ | ✔ (XP ×2) |
| Teleport bez czekania, kolory na czacie i tabliczkach | | | | ✔ | ✔ | ✔ |
| `/back`, śmierć bez kicka, flaga PvP na działce | | | | | ✔ | ✔ |
| `/bruk`, `/piec`, gradienty na czacie | | | | | | ✔ |

DRWAL daje ścinanie całego drzewa jednym uderzeniem. Działa siekierą od drewnianej do diamentowej,
nie działa netherytową, a kucanie wyłącza efekt.

## Uprawnienia pluginu core

| Uprawnienie | Domyślnie | Grupa w LuckPerms | Opis |
|---|---|---|---|
| `kudlacze.gracz` | wszyscy | default | podstawowe funkcje |
| `kudlacze.dzialka.vip` / `.tytan` | nie | vip / tytan | większe kamienie działek |
| `kudlacze.strefavip`, `kudlacze.expiarka`, `kudlacze.nagroda.codzienna` | nie | vip | strefa VIP |
| `kudlacze.slot.rezerwacja` | nie | vip, pomocnik | wejście na pełny serwer |
| `kudlacze.tabliczki.kolory`, `kudlacze.czat.kolory` | nie | mvip | kolory |
| `kudlacze.death.nokick` | nie | uvip, pomocnik | brak kicka po śmierci |
| `kudlacze.bruk`, `kudlacze.piec`, `kudlacze.czat.gradienty` | nie | tytan | perki TYTAN |
| `kudlacze.drwal` | nie | drwal | ścinanie drzew |
| `kudlacze.youtuber.reklama` | nie | youtuber | `/yt` |
| `kudlacze.afk.bypass` | nie | pomocnik | brak kicka za AFK (nagrody za czas gry i tak nie naliczają się podczas AFK) |
| `kudlacze.personel` | op | pomocnik | powiadomienia: podróbki KC, reklama na czacie, makro AFK |
| `kudlacze.czat.bez-filtra` | op | admin (`*`) | pomija filtry czatu |
| `kudlacze.chaty.admin` | op | moderator | ocena Kudłatych Chat |
| `kudlacze.kc.podglad` | op | viceadmin | podgląd KC innych |
| `kudlacze.smok.admin`, `kudlacze.edycja.end.omin` | op | viceadmin | smok, wejście do zamkniętego Endu |
| `kudlacze.kc.admin`, `kudlacze.admin`, `kudlacze.sezon.admin`, `kudlacze.edycja.admin`, `kudlacze.tytul.admin`, `kudlacze.sklep.admin`, `kudlacze.lobby.buduj` | op | admin (`*`) | administracja |

## Personel — skrót

| Grupa | Najważniejsze uprawnienia |
|---|---|
| pomocnik | ostrzeżenia, wyciszenia, kicki (LibertyBans), CoreProtect inspect/lookup, alerty Grim, `/helpop`, `/tp` |
| moderator | bany i tempbany, rollback/restore, vanish, invsee, gamemode (spectator), podgląd działek, Kudłate Chaty |
| viceadmin | bany IP, pełny WorldGuard/WorldEdit, `ps admin`, podgląd KC, smok |
| admin | wszystko (`*`) |
| technik | diagnostyka (spark, tps, mspt, plugins, `lp … info`); zanegowane: `essentials.give/item/more/eco`, `minecraft.command.give`, `kudlacze.kc.admin` |
