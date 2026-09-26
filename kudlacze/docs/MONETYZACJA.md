# Monetyzacja i zgodność z EULA

Kudłacze trzymają się [EULA Minecrafta](https://www.minecraft.net/eula) i
[Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines):

- **nic, co daje przewagę w grze, nie jest sprzedawane za prawdziwe pieniądze**;
- Kłaki Czarodzieja (KC) i rangi zdobywa się wyłącznie grając;
- sprzedaż, jeśli kiedykolwiek zostanie włączona, może dotyczyć tylko kosmetyki;
- sklep jest **wyłączony** (`sklep.wlaczony: false`), a `/sklep` informuje graczy, że wszystko zdobywa się w grze.

## Mechaniki: gameplay czy cosmetic

Oznaczenia:
- **gameplay**: wpływa na rozgrywkę; zdobywane wyłącznie w grze, **nigdy nie do kupienia**;
- **cosmetic**: wyłącznie wygląd; dziś też tylko w grze, a technicznie mogłoby trafić do sklepu kosmetycznego.

| Mechanika | Typ | Jak zdobyć | Do kupienia za pieniądze? |
|---|---|---|---|
| Kłaki Czarodzieja (KC), saldo i przedmiot | gameplay | questy, Skarbnik, ranking sezonu, Smok Kudłaty, Kudłate Chaty, handel między graczami | **nie** |
| Rangi VIP, SVIP, MVIP, UVIP, TYTAN | gameplay | 80 KC każda u Czarodzieja Kudłacza | **nie** |
| Ranga DRWAL | gameplay | 40 KC | **nie** |
| Działki: limit i rozmiar | gameplay | ranga (za KC) | **nie** |
| Domy (`/sethome`) | gameplay | ranga (za KC) | **nie** |
| Strefa VIP, expiarka, codzienna nagroda | gameplay | ranga VIP+ | **nie** |
| `/ec`, `/wb`, `/back`, `/bruk`, `/piec`, mnożnik XP, teleport bez czekania | gameplay | rangi | **nie** |
| Brak kicka po śmierci | gameplay | ranga UVIP+ | **nie** |
| Rezerwacja slotu | gameplay | ranga VIP+ | **nie** |
| Pieniądze z gry (zł, EssentialsX) | gameplay | gra, Skarbnik, handel | **nie** |
| Kryształy sezonu i sklep kryształów | gameplay | wyzwania sezonu | **nie** |
| Punkty rankingu sezonu | gameplay | questy, czas gry, wyzwania, smok | **nie** |
| Kolory i gradienty na czacie i tabliczkach | cosmetic | rangi MVIP / TYTAN | nie (element rangi) |
| Tytuły (Smokobójca, Mistrz Sezonu, Mistrz Chat…) | cosmetic | osiągnięcia w grze | nie — tytuły osiągnięć są wyłącznie za grę |
| Prefiksy rang w TAB i na czacie | cosmetic | ranga | nie (element rangi) |
| Ranga YouTuber | cosmetic | decyzja administracji | nie |
| Wygląd Kłaka (resource pack) | cosmetic | dla wszystkich | nie dotyczy |
| Sklep kosmetyczny: `TYTUL`, `KOLOR_NICKU`, `CZASTECZKI` | cosmetic | — | **tylko te typy są technicznie dozwolone; sklep wyłączony** |

## Zabezpieczenia w kodzie

- `ShopCatalog` (moduł `sklep`) odrzuca przy starcie każdą pozycję sklepu spoza typów `TYTUL`, `KOLOR_NICKU`
  i `CZASTECZKI`. Test `ShopCatalogTest.gameplayAdvantagesAreRejected` sprawdza, że typy `RANGA`, `KC`,
  `PRZEDMIOT`, `WALUTA` i `DZIALKA` nie przejdą walidacji.
- Plugin nie ma integracji płatności ani komendy, która zamieniałaby płatność na KC lub rangę. KC powstają tylko
  z nagród w grze (typy transakcji: `NAGRODA_CODZIENNA`, `QUEST`, `WYZWANIE`, `SMOK`, `RANKING_SEZONU`, `KONKURS`)
  oraz z komend administracji. Każda transakcja trafia do `kc_transakcje`.
- `/kc daj|ustaw` ma tylko administracja (`kudlacze.kc.admin`), a technik ma je zanegowane. Zmiana salda
  zapisuje się z typem `ADMIN` i autorem.
- Regulamin zakazuje handlu KC, rangami i przedmiotami za prawdziwe pieniądze.

## Jeśli kiedyś włączycie sklep kosmetyczny

1. Wybierz dostawcę płatności zgodnego z polskim prawem i zasadami Mojang. Dotyczy to faktur, praw konsumenta i
   ograniczeń dla niepełnoletnich.
2. Dodaj pozycje w `sklep.pozycje` wyłącznie z typami kosmetycznymi i ustaw `sklep.wlaczony: true`.
3. Wydawanie zakupów: `/sklep wydaj <nick> <pozycja> <nr zamówienia>`. Zapis trafia do `sklep_zakupy`.
   Tytuł kosmetyczny nadaje się przez pole `nadaje`.
4. Tytuły osiągnięć (Smokobójca, Mistrz Sezonu, Mistrz Chat, Architekt, Budowniczy) **nie** mogą trafić do sklepu.
   Muszą mieć osobne identyfikatory, żeby nie deprecjonować osiągnięć.
5. Nie sprzedawaj zestawów „kosmetyka + cokolwiek z tabeli gameplay”.
