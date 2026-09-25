-- Kudłacze core — V3: konkurs Kudłate Chaty, tytuły kosmetyczne, parkour, struktura sklepu kosmetycznego.

CREATE TABLE IF NOT EXISTS konkurs_zgloszenia (
    edycja INT NOT NULL,
    uuid CHAR(36) NOT NULL,
    nick VARCHAR(32) NOT NULL,
    swiat VARCHAR(64) NOT NULL,
    x DOUBLE NOT NULL,
    y DOUBLE NOT NULL,
    z DOUBLE NOT NULL,
    opis VARCHAR(255),
    zgloszono BIGINT NOT NULL,
    ocena INT,
    oceniajacy CHAR(36),
    PRIMARY KEY (edycja, uuid)
);

CREATE TABLE IF NOT EXISTS core_tytuly (
    uuid CHAR(36) NOT NULL,
    tytul VARCHAR(64) NOT NULL,
    zrodlo VARCHAR(64) NOT NULL,
    nadano BIGINT NOT NULL,
    PRIMARY KEY (uuid, tytul)
);

CREATE TABLE IF NOT EXISTS core_aktywny_tytul (
    uuid CHAR(36) NOT NULL PRIMARY KEY,
    tytul VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS parkour_czasy (
    uuid CHAR(36) NOT NULL PRIMARY KEY,
    nick VARCHAR(32) NOT NULL,
    najlepszy_ms BIGINT NOT NULL,
    ustanowiono BIGINT NOT NULL
);

-- Sklep kosmetyczny: WYŁĄCZONY (config sklep.wlaczony=false). Tylko struktura danych —
-- pozycje mogą być wyłącznie kosmetyczne (walidacja w kodzie), bez przewag w grze (EULA).
CREATE TABLE IF NOT EXISTS sklep_pozycje (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    typ VARCHAR(32) NOT NULL,
    nazwa VARCHAR(128) NOT NULL,
    cena_grosze INT NOT NULL,
    aktywna BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS sklep_zakupy (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid CHAR(36) NOT NULL,
    pozycja VARCHAR(64) NOT NULL,
    zamowienie VARCHAR(128) NOT NULL,
    czas BIGINT NOT NULL
);
