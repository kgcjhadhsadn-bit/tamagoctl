# Ekonomia Kłaków Czarodzieja (KC)

Cel balansu: **aktywny gracz zdobywa ok. 20 KC tygodniowo**, więc pierwszą rangę (VIP, 80 KC) ma po ok. 4
tygodniach, a DRWALA (40 KC) po 2. Rangi trwają 30 dni albo do nowej edycji, więc utrzymanie rangi wymaga
regularnej gry.

Wszystkie liczby poniżej pochodzą z plików pluginu: `config.yml`, `questy.yml` i `rangi.yml`. Symulację liczy test
`pl.kudlacze.core.economy.EconomyTest`. Jeśli ktoś zmieni balans i nie zaktualizuje tego dokumentu, test się wywali.

## Źródła KC

| Źródło | Ile | Tygodniowo (aktywny gracz) | Skąd |
|---|---|---|---|
| Questy Kwatermistrza (seasons) | 1–2 KC za quest, limity dzienne 1–2 na quest | **10 KC** — tygodniowy limit KC z questów | `questy.yml: limit-kc-tygodniowo: 10` |
| Codzienna nagroda Skarbnika (VIP+) | losowo: 50% → 1 KC, 10% → 2 KC, 25% → 250 zł, 15% → przedmioty | **ok. 4,9 KC** (0,7 KC × 7 dni) | `config.yml: nagroda-codzienna` |
| Smok Kudłaty (niedziela 20:00) | top 5: 10 / 8 / 6 / 4 / 2 KC | **0–10 KC** (typowo ok. 5) | `config.yml: smok.kc` |
| **Razem** | | **ok. 20 KC** (10 + 4,9 + 5 = 19,9) | `EconomyTest` |

Źródła okazjonalne (poza tygodniowym rachunkiem):

| Źródło | Nagroda |
|---|---|
| Koniec sezonu (1. dnia miesiąca) — top 3 rankingu | 50 / 30 / 20 KC + tytuł |
| Kudłate Chaty (koniec edycji) — top 3 | 60 / 40 / 20 KC + tytuł |
| Handel między graczami | `/kc przelej`, `/wyplac` → przedmiot KC |

Gracz bez rangi nie ma dostępu do Skarbnika, więc na start ma ok. 10–15 KC tygodniowo (questy i smok).
VIP zdobywa po ok. 5–6 tygodniach, a potem utrzymuje rangę w ok. 4 tygodnie z ok. 20 KC tygodniowo.
To celowy „próg wejścia”.

## Wydatki

| Wydatek | Koszt | Czas |
|---|---|---|
| VIP, SVIP, MVIP, UVIP, TYTAN (każda wymaga poprzedniej) | 80 KC | 30 dni (zakup przedłuża) albo do nowej edycji |
| DRWAL | 40 KC | 30 dni |

Pełna drabinka do TYTANA kosztuje 400 KC, czyli ok. 20 tygodni aktywnej gry. Każda ranga trwa 30 dni, więc
utrzymanie wysokiej rangi to stały wysiłek. Brak rangi niczego nie blokuje w podstawowej rozgrywce.

## Zabezpieczenia

- **Limit tygodniowy questów** (poniedziałek 00:00 – niedziela, Europe/Warsaw) przycina KC, ale nie punkty
  rankingu. Grind podnosi miejsce w rankingu, a nie inflację KC.
- **Limity dzienne questów** (1–2 oddania na quest) rozkładają zarobek na cały tydzień.
- **Anty-AFK:** gracz AFK nie dostaje punktów za czas gry. Makro/autoklikacz nie przedłuża aktywności.
- **Wyzwania** dają kryształy tylko raz na sezon. Kopanie z Jedwabnym dotykiem się nie liczy (brak
  zapętlenia rudy), a moby ze spawnerów i jajek też nie.
- **Smok:** do klasyfikacji trzeba zadać co najmniej 10 obrażeń (ochrona przed „jednym strzałem z alta”).
- **Przedmioty KC** mają partię i podpis HMAC. Podróbki (np. `/give` odłamka ametystu) i duplikaty
  (przekroczenie wypłaconej partii) są konfiskowane przy `/wplac`, a personel dostaje alert.
- **Dziennik transakcji:** każda zmiana salda trafia do `kc_transakcje` z typem, opisem, serwerem i saldem po operacji.

## Pieniądze z gry (zł)

Obok KC działa ekonomia EssentialsX (zł, `/bal`, `/pay`) do handlu między graczami i zakupu kamieni działek
(`/ps get dzialka` za 250 zł). Saldo zł resetuje się z nową edycją survivalu i nowym sezonem Seasons.
Saldo KC przechodzi na kolejną edycję.
