# Instalacja na VPS

Docelowa maszyna ma 8 vCPU, 16 GB RAM i ok. 100 GB SSD, z systemem Ubuntu 24.04 LTS lub Debian 12. Sieć jest
przewidziana na 100 graczy (survival 60, seasons 30, lobby 10), a Velocity przyjmuje maksymalnie 150.

## 1. System i zapora

```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install ca-certificates curl git python3 python3-yaml ufw unzip
sudo timedatectl set-timezone Europe/Warsaw

# zapora: SSH, Minecraft Java, Bedrock (Geyser)
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw allow 25565/tcp
sudo ufw allow 19132/udp
sudo ufw enable
```

Backendy (lobby/survival/seasons), MariaDB i RCON **nie są wystawione** na zewnątrz. Działają wyłącznie
w wewnętrznej sieci Dockera `kudlacze`.

> Docker omija ufw dla portów publikowanych w `ports:`. Publikowane są tylko 25565/tcp i 19132/udp Velocity,
> więc to niczego nie zmienia. Nie dodawaj `ports:` do backendów ani do MariaDB.

## 2. Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"    # wyloguj się i zaloguj ponownie
docker compose version             # wymagany Compose v2
```

Do zbudowania pluginu core potrzebny jest JDK 25, np. Temurin z https://adoptium.net. Alternatywnie
zbuduj jar lokalnie i skopiuj `build/plugins/common/KudlaczeCore.jar` na serwer.

## 3. Pobranie i konfiguracja

```bash
sudo mkdir -p /opt/kudlacze && sudo chown "$USER" /opt/kudlacze
git clone <repozytorium> /opt/kudlacze-repo
ln -s /opt/kudlacze-repo/kudlacze /opt/kudlacze   # albo skopiuj katalog kudlacze/
cd /opt/kudlacze
./scripts/init-env.sh
```

Uzupełnij `.env` (plik nigdy nie trafia do repozytorium):

| Zmienna | Opis |
|---|---|
| `SERVER_DOMAIN` | domena serwera (np. `kudlacze.pl`) — używana w MOTD, komunikatach, statusie Discorda |
| `COMPOSE_FILE=docker-compose.yml` | **odkomentuj na VPS** — pełne limity pamięci (bez `docker-compose.override.yml`) |
| `MARIADB_ROOT_PASSWORD`, `DB_PASSWORD`, `VELOCITY_SECRET`, `RCON_PASSWORD`, `KC_ITEM_SECRET` | generuje `init-env.sh` — **nie zmieniaj `KC_ITEM_SECRET` po starcie** (unieważnia wypłacone Kłaki) |
| `DISCORD_BOT_TOKEN`, `DISCORD_CHANNEL_*`, `DISCORD_ROLE_*`, `DISCORD_INVITE` | patrz punkt 6 |
| `RESOURCE_PACK_URL`, `RESOURCE_PACK_SHA1` | patrz punkt 7 |

Pamięć (limity kontenerów w `docker-compose.yml`):

| Usługa | Limit | Sterta Javy |
|---|---|---|
| velocity | 1 GB | 512 MB |
| lobby | 2 GB | 1,25 GB |
| survival | 6 GB | 4,75 GB |
| seasons | 4 GB | 3 GB |
| mariadb | 1,5 GB | bufor InnoDB 768 MB |

Różnica między limitem a stertą to pamięć poza stertą: metaspace, JIT, Netty, pluginy. Bez niej cgroup zabija JVM.

## 4. Start

```bash
./scripts/download-plugins.sh      # pobiera jary z plugins.lock.yml i sprawdza SHA256
./scripts/build-core.sh            # plugin KudlaczeCore
docker compose up -d
docker compose ps                  # wszystkie usługi „healthy” po ok. 1–2 min
./scripts/apply-permissions.sh     # grupy i uprawnienia LuckPerms (idempotentne)
./scripts/check-logs.sh
```

Przy pierwszym starcie survival i seasons budują plac spawnu, NPC, hologramy i granice świata. Uruchamiają też
pregenerację Chunky w tle (survival 5000×5000 + Nether 1000×1000, seasons 3000×3000). Postęp widać przez
`docker compose exec survival rcon-cli "chunky progress"`.

Administratora nadaj po pierwszym wejściu:

```bash
docker compose exec survival rcon-cli "lp user <nick> parent set admin"
```

## 5. Cron: backup 04:00 i restart 05:00

Codzienny restart o 05:00 z ostrzeżeniami 15/5/1 min robi sam plugin core. Kontener wstaje ponownie dzięki
`restart: unless-stopped`. Kopia zapasowa idzie z crona hosta godzinę wcześniej:

```bash
crontab -e
# kopia: światy (save-off / save-all flush), dane pluginów, dump MariaDB; rotacja 14 dni
0 4 * * * cd /opt/kudlacze && ./scripts/backup.sh >> backups/backup.log 2>&1
```

Crona uruchamiaj jako użytkownik z dostępem do Dockera. Pliki w `data/` należą do UID 1000 (użytkownik
kontenerów), więc backup musi je czytać: uruchamiaj go jako root albo jako użytkownik z UID 1000.
Kopie trafiają do `backups/RRRR-MM-DD_GGMM/` z sumami `SHA256SUMS`. Warto je dodatkowo wysyłać poza VPS
(rclone/rsync na zewnętrzny magazyn).

Przywracanie (przykład dla survivalu):

```bash
docker compose stop survival
mv data/survival/world data/survival/world.bak
tar -xzf backups/<data>/survival.tar.gz -C data/survival
gunzip -c backups/<data>/mariadb.sql.gz | docker compose exec -T mariadb sh -c 'mariadb -uroot -p"$MARIADB_ROOT_PASSWORD"'
docker compose start survival
```

## 6. DNS i Discord

**DNS:**
- rekord `A` dla domeny (lub `play.<domena>`) → IP VPS;
- opcjonalnie rekord `SRV` `_minecraft._tcp.<domena>` → `play.<domena>:25565`;
- Bedrock łączy się na `<domena>:19132`.

**Discord (DiscordSRV na survivalu i seasons):**
1. Utwórz aplikację i bota na https://discord.com/developers. Włącz *Server Members Intent* i *Message Content Intent*, a potem zaproś bota na serwer.
2. W `.env` ustaw `DISCORD_BOT_TOKEN`.
3. W `.env` ustaw ID kanałów (Tryb dewelopera → „Kopiuj ID”): `DISCORD_CHANNEL_CZAT_SURVIVAL`, `…_CZAT_SEASONS`, `…_LOGI_KAR`, `…_RANKING`, `…_SMOK`, `…_KONSOLA`.
4. W `.env` ustaw ID ról `DISCORD_ROLE_*` (VIP…TYTAN, DRWAL, YOUTUBER, personel). Synchronizacja działa jednokierunkowo, z LuckPerms do Discorda, po połączeniu konta komendą `/discord link`.
5. Uruchom `docker compose up -d survival seasons`.

Ogłoszenia smoka (#smok) i końca sezonu (#ranking) wysyła plugin core. Logi kar LibertyBans (#logi-kar)
odczytuje moduł `logi-kar` na survivalu i obejmuje kary z całej sieci. Adresy IP nigdy nie trafiają na Discorda.

## 7. Resource pack (wygląd Kłaków)

```bash
./scripts/build-resourcepack.sh    # build/resourcepack/kudlacze-resourcepack.zip + SHA1
```

Wrzuć ZIP na hosting HTTPS, np. GitHub Releases albo serwer WWW. Wpisz `RESOURCE_PACK_URL` i `RESOURCE_PACK_SHA1`
w `.env`, a potem uruchom `docker compose up -d lobby survival seasons`. Paczka jest opcjonalna
(`require-resource-pack=false`). Bez niej Kłak wygląda jak odłamek ametystu z połyskiem.

## 8. Aktualizacje

```bash
git pull
./scripts/update-lock.sh           # sprawdza nowsze wersje (bez zmian w locku)
./scripts/update-lock.sh --apply   # aktualizuje plugins.lock.yml (sprawdź zmiany przed commitem!)
./scripts/download-plugins.sh && ./scripts/build-core.sh
docker compose up -d && ./scripts/check-logs.sh
```

## Checklista wdrożenia

- [ ] VPS: strefa czasowa `Europe/Warsaw`, aktualizacje systemu, ufw (22/tcp, 25565/tcp, 19132/udp)
- [ ] Docker + Compose v2, użytkownik w grupie `docker`
- [ ] `.env`: `init-env.sh`, `SERVER_DOMAIN`, **`COMPOSE_FILE=docker-compose.yml`**, sekrety nie w repo
- [ ] `download-plugins.sh` bez błędów SHA256, `build-core.sh` z zielonymi testami
- [ ] `docker compose up -d`, wszystkie usługi `healthy`, `apply-permissions.sh`
- [ ] `check-logs.sh`: brak podejrzanych linii poza znanymi (np. brak tokenu Discorda przed konfiguracją)
- [ ] DNS (A/SRV) → wejście z klienta Java i Bedrock
- [ ] Admin: `lp user <nick> parent set admin`, test `/kadmin moduly` na każdym serwerze
- [ ] Discord: token, kanały, role → `docker compose up -d survival seasons`, test `/discord link`
- [ ] Resource pack: `build-resourcepack.sh`, hosting, URL + SHA1 w `.env`
- [ ] Cron backupu 04:00 → test `./scripts/backup.sh` i kopia poza VPS
- [ ] Przejrzyj `docs/REGULAMIN.md` i link Discorda (`DISCORD_INVITE`) — widoczne w grze (`/regulamin`, TAB)
- [ ] Po tygodniu sprawdź pierwszy smok (niedziela 20:00) i reset sezonu (1. dzień miesiąca 12:00)
