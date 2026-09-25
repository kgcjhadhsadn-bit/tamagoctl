-- Kudłacze core — V1: gracze, Kłaki Czarodzieja (KC), rangi, cooldowny, blokady po śmierci.
-- Czas zawsze jako milisekundy epoki (UTC). Składnia zgodna z MariaDB i H2 (MODE=MariaDB).

CREATE TABLE IF NOT EXISTS core_kv (
    k VARCHAR(128) NOT NULL PRIMARY KEY,
    v VARCHAR(4000) NOT NULL
);

CREATE TABLE IF NOT EXISTS core_gracze (
    uuid CHAR(36) NOT NULL PRIMARY KEY,
    nick VARCHAR(32) NOT NULL,
    pierwsze_wejscie BIGINT NOT NULL,
    ostatnie_wejscie BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_gracze_nick ON core_gracze (nick);

CREATE TABLE IF NOT EXISTS kc_saldo (
    uuid CHAR(36) NOT NULL PRIMARY KEY,
    saldo BIGINT NOT NULL DEFAULT 0
);

-- każda zmiana salda KC (wpłata, wypłata, nagroda, zakup rangi, korekta admina)
CREATE TABLE IF NOT EXISTS kc_transakcje (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid CHAR(36) NOT NULL,
    zmiana BIGINT NOT NULL,
    saldo_po BIGINT NOT NULL,
    typ VARCHAR(32) NOT NULL,
    zrodlo VARCHAR(128),
    serwer VARCHAR(32),
    czas BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_kc_tx_uuid ON kc_transakcje (uuid, czas);

-- fizyczne partie KC (przedmioty): pozostało = ile jeszcze można wpłacić (ochrona przed dupe)
CREATE TABLE IF NOT EXISTS kc_przedmioty (
    id CHAR(36) NOT NULL PRIMARY KEY,
    ilosc BIGINT NOT NULL,
    pozostalo BIGINT NOT NULL,
    wydano_dla CHAR(36) NOT NULL,
    wydano BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS core_rangi (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid CHAR(36) NOT NULL,
    ranga VARCHAR(32) NOT NULL,
    koszt BIGINT NOT NULL,
    kupiono BIGINT NOT NULL,
    wygasa BIGINT NOT NULL,
    edycja INT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rangi_uuid ON core_rangi (uuid);

CREATE TABLE IF NOT EXISTS core_cooldowny (
    uuid CHAR(36) NOT NULL,
    klucz VARCHAR(64) NOT NULL,
    do_kiedy BIGINT NOT NULL,
    PRIMARY KEY (uuid, klucz)
);

CREATE TABLE IF NOT EXISTS core_blokady_smierci (
    uuid CHAR(36) NOT NULL,
    serwer VARCHAR(32) NOT NULL,
    do_kiedy BIGINT NOT NULL,
    PRIMARY KEY (uuid, serwer)
);
