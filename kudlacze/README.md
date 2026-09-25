# Kudłacze — sieć serwerów Minecraft

Survival + działki, Seasons z miesięcznym resetem i lobby za proxy Velocity,
w stylu klasycznych polskich serwerów „Survival + Działki”. Paper 26.2, Java 25, Docker.

Szybki start (lokalnie):

```bash
./scripts/init-env.sh          # tworzy .env z losowymi hasłami
./scripts/download-plugins.sh  # Paper, Velocity i pluginy z plugins.lock.yml (SHA256)
./scripts/build-core.sh        # własny plugin core
docker compose up -d
./scripts/check-logs.sh        # przegląd logów
```

Pełna dokumentacja: [docs/README.md](docs/README.md).
