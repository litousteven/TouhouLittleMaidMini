#!/usr/bin/env python3
"""Check that a packaged mod jar is self-contained.

A Gradle build can look healthy while the jar it produced is unusable: project
dependencies are not packed into the jar, so the mod loads and then dies on its
first own-class reference. `:fabric:build` now guards the bundled packages, but the
release record also wants proof that every Minecraft symbol the mod references
exists in the vanilla jar it targets.

Usage:
    python scripts/verify-packaged-jar.py <mod.jar> [<minecraft-merged.jar>]

When the second argument is omitted the script looks for the Loom cache entry of
the version in gradle.properties.
"""

import re
import sys
import zipfile
from pathlib import Path

SELF = re.compile(rb"com/touhoulittlemaid/mini/[A-Za-z0-9/$]+")
MC = re.compile(rb"net/minecraft/[A-Za-z0-9/$]+")


def read_properties(path: Path) -> dict:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def find_vanilla_jar(root: Path) -> Path:
    version = read_properties(root / "gradle.properties")["minecraft_version"]
    cache = Path.home() / ".gradle" / "caches" / "fabric-loom" / version / "minecraft-merged.jar"
    if not cache.is_file():
        raise SystemExit(f"vanilla jar not found at {cache}; pass it as the second argument")
    return cache


def scan(jar: Path, pattern: re.Pattern) -> set:
    refs = set()
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith(".class"):
                continue
            for ref in pattern.findall(archive.read(name)):
                refs.add(ref.decode())
    return refs


def main() -> int:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    root = Path(__file__).resolve().parent.parent
    mod_jar = Path(sys.argv[1])
    vanilla = Path(sys.argv[2]) if len(sys.argv) > 2 else find_vanilla_jar(root)

    with zipfile.ZipFile(mod_jar) as archive:
        entries = {n[:-6] for n in archive.namelist() if n.endswith(".class")}

    own = scan(mod_jar, SELF)
    missing_own = sorted(own - entries)
    with zipfile.ZipFile(vanilla) as archive:
        vanilla_entries = {n[:-6] for n in archive.namelist() if n.endswith(".class")}
    minecraft = scan(mod_jar, MC)
    missing_minecraft = sorted(minecraft - vanilla_entries)

    print(f"jar: {mod_jar.name}")
    print(f"class files: {len(entries)}")
    print(f"own classes referenced: {len(own)}, missing: {len(missing_own)}")
    for ref in missing_own[:20]:
        print(f"  MISSING {ref}")
    print(f"Minecraft symbols referenced: {len(minecraft)}, absent from vanilla: {len(missing_minecraft)}")
    for ref in missing_minecraft[:20]:
        print(f"  ABSENT {ref}")

    if missing_own or missing_minecraft:
        print("FAIL")
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
