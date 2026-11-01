"""Verify exact stable Paper native inputs without downloading any server binaries."""

import copy
import json
from pathlib import Path
import unittest

from scripts.runtime.prepare_backpack_probe_inputs import validate_pins

ROOT = Path(__file__).resolve().parents[1]
PINS = ROOT / "scripts/runtime/backpack_probe_inputs.json"
HISTORICAL = ROOT / "scripts/runtime/paper_26_3_beta_159_historical.json"
STABLE_SHA = "4ebaf5c9da98c654bb8f17245799943dfb05a285830be4307bfdf7bc9ca024bf"
BETA_SHA = "2224a0b2b6b096ff4c429ad926e97977213e4f633e90cb3a49b5eeb82f94bab0"


class StablePaperRuntimePinTest(unittest.TestCase):
    def setUp(self):
        self.pins = json.loads(PINS.read_text(encoding="utf-8"))

    def test_all_three_native_lanes_are_exact_stable_pins(self):
        validate_pins(self.pins)
        self.assertEqual(set(self.pins["paper"]), {"1.21.11", "26.2", "26.3"})
        for version, pin in self.pins["paper"].items():
            with self.subTest(version=version):
                self.assertEqual(pin["channel"], "STABLE")
                self.assertEqual(pin["metadata_url"],
                                 f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds/{pin['build']}")

    def test_26_3_stable_identity_is_exactly_the_official_reviewed_proposal(self):
        pin = self.pins["paper"]["26.3"]
        self.assertEqual(pin["build"], 235)
        self.assertEqual(pin["name"], "paper-26.3-235.jar")
        self.assertEqual(pin["size"], 55261122)
        self.assertEqual(pin["sha256"], STABLE_SHA)
        self.assertEqual(pin["url"],
                         f"https://fill-data.papermc.io/v1/objects/{STABLE_SHA}/paper-26.3-235.jar")

    def test_historical_beta_identity_is_retained_but_not_selected(self):
        history = json.loads(HISTORICAL.read_text(encoding="utf-8"))
        self.assertTrue(history["historical_only"])
        self.assertEqual(history["paper"]["build"], 159)
        self.assertEqual(history["paper"]["channel"], "BETA")
        self.assertEqual(history["paper"]["sha256"], BETA_SHA)
        self.assertNotEqual(history["paper"]["sha256"], self.pins["paper"]["26.3"]["sha256"])

    def test_published_core_historical_fixture_is_not_replaced(self):
        core = self.pins["core"]
        self.assertEqual(core["version"], "4.1.71")
        self.assertEqual(core["source_commit"], "f68544e47d8489704d0e9d34adc65f0114b07153")
        self.assertEqual(core["sha256"], "916b055a6917c87405078948fd00cdbb2394078248d1c9bfbbfa9c4b37106e6a")

    def test_beta_masquerading_as_stable_is_rejected_before_download(self):
        modified = copy.deepcopy(self.pins)
        modified["paper"]["26.3"]["channel"] = "BETA"
        with self.assertRaisesRegex(ValueError, "exact STABLE"):
            validate_pins(modified)

    def test_bad_build_id_is_rejected_before_download(self):
        for invalid in ("235", 0, True):
            with self.subTest(invalid=invalid):
                modified = copy.deepcopy(self.pins)
                modified["paper"]["26.3"]["build"] = invalid
                with self.assertRaises(ValueError):
                    validate_pins(modified)

    def test_modified_metadata_endpoint_is_rejected(self):
        modified = copy.deepcopy(self.pins)
        modified["paper"]["26.3"]["metadata_url"] += "?latest=true"
        with self.assertRaisesRegex(ValueError, "official metadata URL"):
            validate_pins(modified)


if __name__ == "__main__":
    unittest.main()
