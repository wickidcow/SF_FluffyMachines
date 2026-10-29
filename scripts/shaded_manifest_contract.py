#!/usr/bin/env python3
"""Check that Maven Shade keeps the canonical project manifest in the raw plugin JAR."""
from pathlib import Path
from xml.etree import ElementTree as ET
import argparse
import zipfile

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
DEPENDENCIES = (
    "org.bstats:bstats-base",
    "org.bstats:bstats-bukkit",
    "com.github.Slimefun-Addon-Community:ExtraUtils",
)
MANIFEST = "META-INF/MANIFEST.MF"


def verify_pom(source: str) -> None:
    root = ET.fromstring(source)
    plugins = [
        p for p in root.findall("m:build/m:plugins/m:plugin", NS)
        if p.findtext("m:artifactId", namespaces=NS) == "maven-shade-plugin"
    ]
    if len(plugins) != 1:
        raise ValueError("Exactly one Maven Shade plugin is required")
    filters = plugins[0].findall("m:configuration/m:filters/m:filter", NS)
    artifacts = [f.findtext("m:artifact", namespaces=NS) for f in filters]
    if tuple(artifacts) != DEPENDENCIES:
        raise ValueError("Shade manifest exclusions must name only the three bundled dependencies")
    for item in filters:
        excludes = [x.text for x in item.findall("m:excludes/m:exclude", NS)]
        if excludes != ["META-INF/*.MF"]:
            raise ValueError("Only dependency manifests may be filtered; keep other resource behavior")
    if root.findtext("m:properties/m:maven.compiler.release", namespaces=NS) != "21":
        raise ValueError("Java 21 distributable bytecode must be preserved")
    if root.findtext("m:build/m:finalName", namespaces=NS) != "SF_FluffyMachines${project.version}":
        raise ValueError("The canonical raw plugin filename must be preserved")


def _manifest(archive: zipfile.ZipFile) -> bytes:
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError("Duplicate raw JAR entries")
    if names.count(MANIFEST) != 1:
        raise ValueError("Shaded JAR must contain exactly one project manifest")
    if names.count("plugin.yml") != 1:
        raise ValueError("Shaded JAR must contain exactly one plugin descriptor")
    data = archive.read(MANIFEST)
    if not data.startswith(b"Manifest-Version: "):
        raise ValueError("Shaded JAR manifest does not begin with Manifest-Version")
    return data


def verify_jar(shaded: Path, original: Path | None = None) -> None:
    with zipfile.ZipFile(shaded) as jar:
        if jar.testzip() is not None:
            raise ValueError("Invalid compressed raw JAR member")
        current = _manifest(jar)
    if original is not None:
        with zipfile.ZipFile(original) as unshaded:
            expected = _manifest(unshaded)
        if current != expected:
            raise ValueError("Shaded manifest differs from the canonical unshaded project manifest")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--original", type=Path)
    args = parser.parse_args()
    verify_pom((Path(__file__).resolve().parents[1] / "pom.xml").read_text(encoding="utf-8"))
    verify_jar(args.jar, args.original)
    print("Project manifest and dependency-scoped Shade filters: PASS")


if __name__ == "__main__":
    main()
