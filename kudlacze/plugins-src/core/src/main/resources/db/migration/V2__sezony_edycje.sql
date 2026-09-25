-- Kudłacze core — V2: Seasons (punkty, questy, wyzwania, archiwum), Smok Kudłaty, edycje survivalu.

CREATE TABLE IF NOT EXISTS sezon_gracze (
    sezon INT NOT NULL,
    uuid CHAR(36) NOT NULL,
    nick VARCHAR(32) NOT NULL,
    punkty BIGINT NOT NULL DEFAULT 0,
    krysztaly BIGINT NOT NULL DEFAULT 0,
    czas_gry_s BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (sezon, uuid)
);
CREATE INDEX IF NOT EXISTS idx_sezon_punkty ON sezon_gracze (sezon, punkty);

CREATE TABLE IF NOT EXISTS sezon_questy_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sezon INT NOT NULL,
    uuid CHAR(36) NOT NULL,
    quest VARCHAR(64) NOT NULL,
    ilosc INT NOT NULL,
    punkty BIGINT NOT NULL,
    kc BIGINT NOT NULL,
    czas BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_questy_uuid ON sezon_questy_log (sezon, uuid);

CREATE TABLE IF NOT EXISTS sezon_wyzwania (
    sezon INT NOT NULL,
    uuid CHAR(36) NOT NULL,
    wyzwanie VARCHAR(64) NOT NULL,
    postep BIGINT NOT NULL DEFAULT 0,
    ukonczono BIGINT,
    PRIMARY KEY (sezon, uuid, wyzwanie)
);

CREATE TABLE IF NOT EXISTS sezon_archiwum (
    sezon INT NOT NULL,
    miejsce INT NOT NULL,
    uuid CHAR(36) NOT NULL,
    nick VARCHAR(32) NOT NULL,
    punkty BIGINT NOT NULL,
    zakonczono BIGINT NOT NULL,
    PRIMARY KEY (sezon, miejsce)
);

CREATE TABLE IF NOT EXISTS smok_walki (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sezon INT NOT NULL,
    start BIGINT NOT NULL,
    koniec BIGINT,
    zabity BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS smok_obrazenia (
    walka BIGINT NOT NULL,
    uuid CHAR(36) NOT NULL,
    nick VARCHAR(32) NOT NULL,
    obrazenia DOUBLE NOT NULL,
    PRIMARY KEY (walka, uuid)
);

CREATE TABLE IF NOT EXISTS edycje (
    numer INT NOT NULL PRIMARY KEY,
    start BIGINT NOT NULL,
    archiwum VARCHAR(255)
);
