"""Checks the AI model catalog against its source: every file's size and SHA-256.

Run from the repository root with Python 3.10+ (standard library only):

    py tools/models/check_catalog.py [--cache <dir>]    # downloads every catalog file once, then checks it
    py tools/models/check_catalog.py --hash <file>...   # size and SHA-256 of files, for new entries

Reads `ModelCatalog.kt` for the pinned base URLs and each `file("path", bytes, "sha256")` (from
BASE) and `hosted("path", bytes, "sha256")` (from HOSTED_BASE). Files are kept under --cache
(default: a temporary folder, removed afterward) by their path, so a second run downloads
nothing. Exits 1 when any file is missing or differs.
"""
import argparse
import hashlib
import pathlib
import re
import shutil
import sys
import tempfile
import urllib.request

CATALOG = pathlib.Path('app/src/main/java/com/infinity/comicreader/ai/ModelCatalog.kt')


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def entries(text: str) -> list[tuple[str, str, int, str]]:
    """Every catalog file as (URL, cache path, bytes, sha256): `file(...)` from BASE, `hosted(...)` from HOSTED_BASE."""
    commit = re.search(r'const val COMMIT = "([0-9a-f]{40})"', text).group(1)
    base = re.search(r'const val BASE = "([^"]+)"', text).group(1).replace('$COMMIT', commit)
    repository = re.search(r'const val HOSTED_REPOSITORY = "([^"]+)"', text).group(1)
    revision = re.search(r'const val HOSTED_REVISION = "([0-9a-f]{40})"', text).group(1)
    hosted = re.search(r'const val HOSTED_BASE = "([^"]+)"', text).group(1)
    hosted = hosted.replace('$HOSTED_REPOSITORY', repository).replace('$HOSTED_REVISION', revision)
    files = []
    for kind, p, n, h in re.findall(r'\b(file|hosted)\("([^"]+)", (\d+), "([0-9a-f]{64})"\)', text):
        url = (base if kind == 'file' else hosted) + p
        files.append((url, f'{kind}/{p}', int(n), h))
    return files


def check(cache: pathlib.Path) -> int:
    files = entries(CATALOG.read_text(encoding='utf-8'))
    bad = 0
    for url, path, size, digest in files:
        local = cache / path
        if not local.is_file():
            local.parent.mkdir(parents=True, exist_ok=True)
            try:
                with urllib.request.urlopen(url, timeout=60) as r, local.open('wb') as out:
                    shutil.copyfileobj(r, out)
            except OSError as e:
                print(f'MISSING {path}: {e}')
                local.unlink(missing_ok=True)
                bad += 1
                continue
        got_size, got = local.stat().st_size, sha256(local)
        if got_size != size or got != digest:
            print(f'DIFFERS {path}: {got_size} bytes, {got}')
            bad += 1
        else:
            print(f'ok      {path}')
    print(f'{len(files) - bad} of {len(files)} files match')
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--cache', type=pathlib.Path, help='keep downloads here')
    ap.add_argument('--hash', nargs='+', type=pathlib.Path, help='print size and SHA-256 of these files')
    a = ap.parse_args()
    if a.hash:
        for p in a.hash:
            print(f'{p.stat().st_size}\t{sha256(p)}\t{p}')
        return 0
    if a.cache:
        return check(a.cache)
    with tempfile.TemporaryDirectory() as tmp:
        return check(pathlib.Path(tmp))


if __name__ == '__main__':
    sys.exit(main())
