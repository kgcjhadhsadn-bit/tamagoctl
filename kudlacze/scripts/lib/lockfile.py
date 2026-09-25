#!/usr/bin/env python3
"""
Kudłacze — obsługa plugins.lock.yml.

  lockfile.py download          pobiera wszystko z locka, weryfikuje SHA256 i rozkłada
                                pliki do build/jars oraz build/plugins/<cel>
  lockfile.py verify            sprawdza tylko sumy plików już pobranych
  lockfile.py update [--apply]  pyta API (PaperMC Fill, Modrinth, Hangar, GitHub, GeyserMC)
                                o najnowsze wersje zgodne z wersją MC i pokazuje / zapisuje zmiany

Bez zależności spoza biblioteki standardowej (PyYAML jest opcjonalny) — działa na czystym
Ubuntu 24.04. Sieć: urllib respektuje zmienne HTTPS_PROXY/NO_PROXY.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / "plugins.lock.yml"
BUILD = ROOT / "build"
CACHE = BUILD / "cache"
USER_AGENT = "kudlacze-network/1.0 (+https://github.com/kgcjhadhsadn-bit/tamagoctl)"

# cel w locku -> katalog docelowy (względem build/)
TARGET_DIRS = {
    "server-jar": "jars",
    "proxy-jar": "jars",
    "proxy": "plugins/proxy",
    "common": "plugins/common",
    "lobby": "plugins/lobby",
    "survival": "plugins/survival",
    "seasons": "plugins/seasons",
}
ENTRY_FIELDS = ["name", "source", "project", "platform", "asset", "channel", "mc", "version",
                "file", "targets", "url", "sha256", "note"]


# ---------------------------------------------------------------- mini-YAML
def _scalar(v: str):
    v = v.strip()
    if v.startswith("[") and v.endswith("]"):
        inner = v[1:-1].strip()
        return [_scalar(x) for x in inner.split(",")] if inner else []
    if len(v) >= 2 and v[0] == v[-1] == '"':
        return v[1:-1].replace('\\"', '"').replace("\\\\", "\\")
    if len(v) >= 2 and v[0] == v[-1] == "'":
        return v[1:-1].replace("''", "'")
    return v


def load_lock(path: Path = LOCK) -> dict:
    """Parsuje ograniczony podzbiór YAML używany przez plugins.lock.yml."""
    text = path.read_text(encoding="utf-8")
    try:  # jeśli PyYAML jest dostępny — użyj go
        import yaml  # type: ignore
        data = yaml.safe_load(text)
        for e in data.get("entries", []):
            for k, v in list(e.items()):
                if v is not None and not isinstance(v, (list, str)):
                    e[k] = str(v)
        return data
    except ImportError:
        pass
    data: dict = {"entries": []}
    current = None
    in_entries = False
    for raw in text.splitlines():
        line = re.sub(r"\s+#.*$", "", raw) if not raw.lstrip().startswith("#") else ""
        if not line.strip():
            continue
        if not line.startswith(" ") and not line.startswith("-"):
            key, _, val = line.partition(":")
            in_entries = key.strip() == "entries"
            if not in_entries:
                data[key.strip()] = _scalar(val)
            continue
        if not in_entries:
            continue
        stripped = line.strip()
        if stripped.startswith("- "):
            current = {}
            data["entries"].append(current)
            stripped = stripped[2:]
        key, _, val = stripped.partition(":")
        if current is None:
            raise SystemExit(f"Błąd składni locka: {raw}")
        current[key.strip()] = _scalar(val)
    return data


def _q(v: str) -> str:
    if (v == "" or re.search(r"[:#\[\]{},&*!|>'\"%@`\\]", v) or v.strip() != v
            or re.fullmatch(r"[0-9.eE+-]+", v)):
        return '"' + v.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return v


def dump_lock(data: dict, path: Path = LOCK) -> None:
    header = path.read_text(encoding="utf-8").split("\nschema:")[0] if path.exists() else ""
    out = [header.rstrip("\n"), ""] if header else []
    for key in ("schema", "minecraft", "velocity-major"):
        if key in data:
            out.append(f"{key}: {_q(str(data[key]))}")
    out.append("entries:")
    for e in data["entries"]:
        first = True
        for f in ENTRY_FIELDS:
            if f not in e or e[f] in (None, ""):
                continue
            v = e[f]
            val = "[" + ", ".join(v) + "]" if isinstance(v, list) else _q(str(v))
            out.append(("  - " if first else "    ") + f"{f}: {val}")
            first = False
        out.append("")
    path.write_text("\n".join(out).rstrip("\n") + "\n", encoding="utf-8")


# ---------------------------------------------------------------- HTTP
def http_get(url: str, accept: str = "application/json") -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": accept})
    token = os.environ.get("GITHUB_TOKEN")
    if token and urllib.parse.urlparse(url).hostname == "api.github.com" and token != "proxy-injected":
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def http_json(url: str):
    return json.loads(http_get(url))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_to_cache(url: str, expected_sha256: str | None, name: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    if expected_sha256:
        cached = CACHE / f"{expected_sha256[:16]}-{name}"
        if cached.exists() and sha256_file(cached) == expected_sha256:
            return cached
    fd, tmp = tempfile.mkstemp(dir=CACHE, prefix=".part-")
    os.close(fd)
    tmp_path = Path(tmp)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=300) as resp, tmp_path.open("wb") as out:
            shutil.copyfileobj(resp, out)
        actual = sha256_file(tmp_path)
        if expected_sha256 and actual != expected_sha256:
            raise SystemExit(
                f"[BŁĄD] {name}: niezgodna suma SHA256!\n  oczekiwano {expected_sha256}\n  pobrano    {actual}\n  z {url}")
        final = CACHE / f"{actual[:16]}-{name}"
        # mkstemp tworzy plik 0600 — kontenery działają jako uid 1000 i muszą móc go czytać
        tmp_path.chmod(0o644)
        tmp_path.replace(final)
        return final
    finally:
        if tmp_path.exists():
            tmp_path.unlink()


# ---------------------------------------------------------------- download
def cmd_download(args) -> int:
    data = load_lock()
    # czyścimy katalogi docelowe, żeby nie zostały stare wersje jarów
    for sub in set(TARGET_DIRS.values()):
        d = BUILD / sub
        if d.exists() and sub.startswith("plugins"):
            shutil.rmtree(d)
        d.mkdir(parents=True, exist_ok=True)
    missing = []
    for e in data["entries"]:
        name, url, sha = e["name"], e.get("url"), e.get("sha256")
        if not url or not sha:
            missing.append(name)
            continue
        cached = fetch_to_cache(url, sha, e["file"])
        for t in e["targets"]:
            if t not in TARGET_DIRS:
                raise SystemExit(f"[BŁĄD] {name}: nieznany cel '{t}'")
            dest = BUILD / TARGET_DIRS[t] / e["file"]
            shutil.copy2(cached, dest)
            dest.chmod(0o644)
        print(f"[OK] {name:<22} {e.get('version', ''):<28} sha256 {sha[:12]}…  -> {', '.join(e['targets'])}")
    if missing:
        print("[BŁĄD] Brak URL/SHA256 w locku dla: " + ", ".join(missing)
              + "\n       Uruchom: ./scripts/update-lock.sh --apply", file=sys.stderr)
        return 1
    # plugin core (jeśli zbudowany) trafia do wspólnych pluginów backendów
    core_jars = sorted((ROOT / "plugins-src/core/build/libs").glob("KudlaczeCore-*.jar"))
    core_jars = [j for j in core_jars if not j.name.endswith(("-sources.jar", "-javadoc.jar"))]
    if core_jars:
        core_dest = BUILD / "plugins/common" / "KudlaczeCore.jar"
        shutil.copy2(core_jars[-1], core_dest)
        core_dest.chmod(0o644)
        print(f"[OK] {'KudlaczeCore':<22} {core_jars[-1].name:<28} (build lokalny)   -> common")
    else:
        print("[UWAGA] Plugin core nie jest zbudowany — uruchom ./scripts/build-core.sh")
    return 0


def cmd_verify(args) -> int:
    data = load_lock()
    bad = 0
    for e in data["entries"]:
        for t in e["targets"]:
            p = BUILD / TARGET_DIRS[t] / e["file"]
            if not p.exists():
                print(f"[BRAK] {p.relative_to(ROOT)}")
                bad += 1
            elif sha256_file(p) != e.get("sha256"):
                print(f"[ZŁA SUMA] {p.relative_to(ROOT)}")
                bad += 1
    print("Wszystkie pliki zgodne z lockiem." if not bad else f"Problemy: {bad}")
    return 1 if bad else 0


# ---------------------------------------------------------------- update
def resolve_papermc(e: dict, mc: str, data: dict) -> dict:
    project = e["project"]
    base = f"https://fill.papermc.io/v3/projects/{project}"
    if project == "paper":
        version = mc
    else:  # velocity: najnowsza wersja, która ma build w kanale STABLE
        proj = http_json(base)
        versions = [v for group in proj["versions"].values() for v in group]
        version = None
        for v in versions:  # API zwraca od najnowszych; SNAPSHOT-y pomijamy
            if "SNAPSHOT" in v.upper():
                continue
            builds = http_json(f"{base}/versions/{v}/builds")
            if any(b.get("channel") == "STABLE" for b in builds):
                version = v
                break
        if not version:
            raise RuntimeError("brak stabilnej wersji Velocity")
    builds = http_json(f"{base}/versions/{version}/builds")
    stable = [b for b in builds if b.get("channel") == e.get("channel", "STABLE")]
    if not stable:
        raise RuntimeError(f"brak buildów {project} {version} w kanale {e.get('channel', 'STABLE')}")
    b = max(stable, key=lambda x: x["id"])
    dl = b["downloads"]["server:default"]
    return {"version": f"{version}-{b['id']}", "url": dl["url"], "sha256": dl["checksums"]["sha256"]}


def resolve_modrinth(e: dict, mc: str, data: dict) -> dict:
    mc = e.get("mc", mc)  # nadpisanie, gdy autor nie otagował jeszcze bieżącej wersji MC
    loaders = [e.get("platform", "paper")]
    q = urllib.parse.urlencode({"loaders": json.dumps(loaders), "game_versions": json.dumps([mc])})
    if e.get("platform") == "velocity":  # pluginy proxy nie deklarują wersji MC
        q = urllib.parse.urlencode({"loaders": json.dumps(loaders)})
    versions = http_json(f"https://api.modrinth.com/v2/project/{e['project']}/version?{q}")
    allowed = {"release": {"release"}, "beta": {"release", "beta"},
               "alpha": {"release", "beta", "alpha"}}[e.get("channel", "release")]
    versions = [v for v in versions if v["version_type"] in allowed]
    if not versions:
        raise RuntimeError(f"Modrinth: brak wersji {e['project']} dla {loaders} / {mc}")
    v = versions[0]
    files = v["files"]
    f = next((x for x in files if x.get("primary")), files[0])
    if e.get("asset"):
        f = next(x for x in files if re.fullmatch(e["asset"], x["filename"]))
    return {"version": v["version_number"], "url": f["url"], "sha512": f["hashes"]["sha512"]}


def resolve_hangar(e: dict, mc: str, data: dict) -> dict:
    mc = e.get("mc", mc)
    platform = e.get("platform", "PAPER").upper()
    q = urllib.parse.urlencode({"limit": 25, "offset": 0, "platform": platform})
    res = http_json(f"https://hangar.papermc.io/api/v1/projects/{e['project']}/versions?{q}")
    for v in res["result"]:
        if v.get("channel", {}).get("name", "Release") not in (e.get("channel", "Release"),):
            continue
        pv = v.get("platformDependencies", {}).get(platform, [])
        if platform == "PAPER" and pv and not any(mc_matches(x, mc) for x in pv):
            continue
        d = v["downloads"][platform]
        url = d.get("downloadUrl") or d.get("externalUrl")
        sha = (d.get("fileInfo") or {}).get("sha256Hash")
        return {"version": v["name"], "url": url, "sha256": sha}
    raise RuntimeError(f"Hangar: brak wersji {e['project']} dla {platform} {mc}")


def resolve_github(e: dict, mc: str, data: dict) -> dict:
    rel = http_json(f"https://api.github.com/repos/{e['project']}/releases/latest")
    pattern = e["asset"]
    for a in rel["assets"]:
        if re.fullmatch(pattern, a["name"]):
            digest = a.get("digest") or ""
            return {"version": rel["tag_name"], "url": a["browser_download_url"],
                    "sha256": digest.split(":", 1)[1] if digest.startswith("sha256:") else None}
    raise RuntimeError(f"GitHub {e['project']} {rel['tag_name']}: brak pliku pasującego do {pattern}")


def resolve_geysermc(e: dict, mc: str, data: dict) -> dict:
    project, platform = e["project"], e.get("platform", "velocity")
    b = http_json(f"https://download.geysermc.org/v2/projects/{project}/versions/latest/builds/latest")
    dl = b["downloads"][platform]
    url = (f"https://download.geysermc.org/v2/projects/{project}/versions/{b['version']}"
           f"/builds/{b['build']}/downloads/{platform}")
    return {"version": f"{b['version']}-b{b['build']}", "url": url, "sha256": dl["sha256"]}


def _ver(v: str) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", v))


def mc_matches(spec: str, mc: str) -> bool:
    """Hangar podaje wersje jako '26.2', '1.21.x' albo zakresy '1.21-26.2'."""
    spec = spec.strip()
    if "-" in spec:
        lo, hi = spec.split("-", 1)
        return _ver(lo) <= _ver(mc) <= _ver(hi.replace("x", "999"))
    if spec.endswith(".x"):
        return mc.startswith(spec[:-1])
    return spec == mc


RESOLVERS = {
    "papermc": resolve_papermc,
    "modrinth": resolve_modrinth,
    "hangar": resolve_hangar,
    "github": resolve_github,
    "geysermc": resolve_geysermc,
}


def cmd_update(args) -> int:
    data = load_lock()
    mc = data["minecraft"]
    changed = errors = 0
    for e in data["entries"]:
        if args.only and e["name"] not in args.only:
            continue
        src = e["source"]
        if src == "url":
            print(f"[--] {e['name']:<22} źródło 'url' — aktualizacja ręczna")
            continue
        try:
            r = RESOLVERS[src](e, mc, data)
        except Exception as ex:  # noqa: BLE001 — raport zbiorczy
            print(f"[BŁĄD] {e['name']:<22} {src}: {ex}")
            errors += 1
            continue
        if r.get("sha256") is None or "sha512" in r:
            # Modrinth podaje SHA-512 (a GitHub czasem nic) — pobieramy, weryfikujemy i liczymy SHA256
            cached = fetch_to_cache(r["url"], None, e["file"])
            if "sha512" in r:
                h = hashlib.sha512(cached.read_bytes()).hexdigest()
                if h != r["sha512"]:
                    print(f"[BŁĄD] {e['name']}: SHA-512 z Modrinth nie zgadza się z pobranym plikiem")
                    errors += 1
                    continue
            r["sha256"] = sha256_file(cached)
        if (e.get("version"), e.get("url"), e.get("sha256")) != (r["version"], r["url"], r["sha256"]):
            changed += 1
            print(f"[ZMIANA] {e['name']:<20} {e.get('version') or '-'} -> {r['version']}")
            e.update({"version": r["version"], "url": r["url"], "sha256": r["sha256"]})
        else:
            print(f"[OK] {e['name']:<22} {e.get('version')}")
    if args.apply and changed:
        dump_lock(data)
        print(f"Zapisano {changed} zmian w {LOCK.name}. Uruchom ./scripts/download-plugins.sh")
    elif changed:
        print(f"{changed} zmian — uruchom z --apply, żeby zapisać.")
    return 1 if errors else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("download")
    sub.add_parser("verify")
    up = sub.add_parser("update")
    up.add_argument("--apply", action="store_true")
    up.add_argument("--only", nargs="*")
    args = ap.parse_args()
    return {"download": cmd_download, "verify": cmd_verify, "update": cmd_update}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
