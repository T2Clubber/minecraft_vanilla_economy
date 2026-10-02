"""Builds dist/VanillaEconomy-pack.zip from resourcepack/ (reproducible: sorted entries,
fixed timestamps) and prints the SHA-1 to put in server.properties."""
import hashlib
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "resourcepack"
OUT = ROOT / "dist" / "VanillaEconomy-pack.zip"

OUT.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for path in sorted(p for p in SRC.rglob("*") if p.is_file() and p.name != ".DS_Store"):
        info = zipfile.ZipInfo(path.relative_to(SRC).as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, path.read_bytes())

print(OUT.relative_to(ROOT))
print("sha1:", hashlib.sha1(OUT.read_bytes()).hexdigest())
