#!/usr/bin/env python3
"""Mutation tests for dependency-only manifest filtering and real shaded-JAR comparison."""
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.shaded_manifest_contract import verify_jar, verify_pom

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = b"Manifest-Version: 1.0\r\nCreated-By: Maven JAR Plugin\r\n\r\n"
OTHER = b"Manifest-Version: 1.0\r\nCreated-By: Another plugin\r\n\r\n"


class ShadeSourceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pom = (ROOT / "pom.xml").read_text(encoding="utf-8")

    def test_dependency_scoped_filters_are_valid(self):
        verify_pom(self.pom)

    def test_wildcard_filter_can_not_strip_project_manifest(self):
        with self.assertRaises(ValueError):
            verify_pom(self.pom.replace("org.bstats:bstats-base", "*:*"))

    def test_missing_or_changed_dependency_filter_is_rejected(self):
        for old in ("org.bstats:bstats-base", "org.bstats:bstats-bukkit",
                    "com.github.Slimefun-Addon-Community:ExtraUtils"):
            with self.subTest(artifact=old), self.assertRaises(ValueError):
                verify_pom(self.pom.replace(f"<artifact>{old}</artifact>", "<artifact>other:artifact</artifact>"))

    def test_non_manifest_filter_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_pom(self.pom.replace("<exclude>META-INF/*.MF</exclude>",
                                        "<exclude>plugin.yml</exclude>", 1))

    def test_java_21_and_project_jar_name_are_stable(self):
        for old, new in (
            ("<maven.compiler.release>21</maven.compiler.release>",
             "<maven.compiler.release>25</maven.compiler.release>"),
            ("SF_FluffyMachines${project.version}", "Other${project.version}"),
        ):
            with self.subTest(old=old), self.assertRaises(ValueError):
                verify_pom(self.pom.replace(old, new))

    def test_workflow_checks_exact_shaded_and_same_run_raw_jar(self):
        source = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertIn("python3 -m unittest scripts.test_shaded_manifest_contract -v", source)
        self.assertIn('scripts/shaded_manifest_contract.py "$JAR" --original', source)
        self.assertIn('scripts/shaded_manifest_contract.py "dist/$OUTPUT_NAME"', source)


class ShadedArchiveContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.shaded = Path(self.temp.name) / "shaded.jar"
        self.original = Path(self.temp.name) / "original.jar"

    @staticmethod
    def make_jar(path, manifest=MANIFEST, plugin=True, duplicate=False):
        with zipfile.ZipFile(path, "w") as jar:
            if manifest is not None:
                jar.writestr("META-INF/MANIFEST.MF", manifest)
                if duplicate:
                    with __import__("warnings").catch_warnings():
                        __import__("warnings").simplefilter("ignore", UserWarning)
                        jar.writestr("META-INF/MANIFEST.MF", manifest)
            if plugin:
                jar.writestr("plugin.yml", "name: FluffyMachines\n")

    def test_exact_project_manifest_survives_shading(self):
        self.make_jar(self.original)
        self.make_jar(self.shaded)
        verify_jar(self.shaded, self.original)

    def test_missing_or_invalid_manifest_is_rejected(self):
        self.make_jar(self.original)
        for manifest in (None, b"not a manifest", OTHER):
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                self.make_jar(self.shaded, manifest=manifest)
                verify_jar(self.shaded, self.original)

    def test_missing_plugin_descriptor_is_rejected(self):
        self.make_jar(self.shaded, plugin=False)
        with self.assertRaises(ValueError):
            verify_jar(self.shaded)

    def test_duplicate_zip_entries_are_rejected(self):
        self.make_jar(self.shaded, duplicate=True)
        with self.assertRaises(ValueError):
            verify_jar(self.shaded)

    def test_comparison_requires_the_real_unshaded_project_jar(self):
        self.make_jar(self.shaded)
        with self.assertRaises(FileNotFoundError):
            verify_jar(self.shaded, self.original)


if __name__ == "__main__":
    unittest.main()
