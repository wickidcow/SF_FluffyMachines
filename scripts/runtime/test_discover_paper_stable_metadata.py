"""Reject ambiguous or mutable native server proposals before reviewing a new pin."""
import unittest

from scripts.runtime.discover_paper_stable_metadata import newest_stable_id, proposed_pin


DIGEST = "a" * 64
BUILD = 205
VERSION = "26.3"
NAME = f"paper-{VERSION}-{BUILD}.jar"
URL = f"https://fill-data.papermc.io/v1/objects/{DIGEST}/{NAME}"


def metadata():
    return {"id": BUILD, "channel": "STABLE", "version": VERSION, "project": "paper",
            "downloads": {"server:default": {"name": NAME, "url": URL, "size": 54000000,
                                               "checksums": {"sha256": DIGEST}}}}


class StablePaperMetadataTest(unittest.TestCase):
    def test_only_stable_builds_are_candidates(self):
        self.assertEqual(newest_stable_id([
            {"id": 206, "channel": "BETA"}, {"id": 204, "channel": "STABLE"},
            {"id": 205, "channel": "STABLE"}]), 205)

    def test_no_stable_build_is_not_a_beta_fallback(self):
        with self.assertRaisesRegex(ValueError, "No official STABLE"):
            newest_stable_id([{"id": 206, "channel": "BETA"}])

    def test_exact_official_identity_is_preserved(self):
        result = proposed_pin(VERSION, BUILD, metadata())
        self.assertEqual(result["channel"], "STABLE")
        self.assertEqual(result["sha256"], DIGEST)
        self.assertEqual(result["metadata_url"],
                         "https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/205")

    def test_beta_masquerading_as_stable_is_rejected(self):
        changed = metadata()
        changed["channel"] = "BETA"
        with self.assertRaisesRegex(ValueError, "STABLE"):
            proposed_pin(VERSION, BUILD, changed)

    def test_mismatched_build_is_rejected(self):
        changed = metadata()
        changed["id"] = BUILD - 1
        with self.assertRaisesRegex(ValueError, "requested STABLE"):
            proposed_pin(VERSION, BUILD, changed)

    def test_digest_mismatch_in_content_addressed_url_is_rejected(self):
        changed = metadata()
        changed["downloads"]["server:default"]["url"] = URL.replace(DIGEST, "b" * 64)
        with self.assertRaisesRegex(ValueError, "exact server digest"):
            proposed_pin(VERSION, BUILD, changed)

    def test_unexpected_filename_is_rejected(self):
        changed = metadata()
        changed["downloads"]["server:default"]["name"] = "paper-26.3-latest.jar"
        with self.assertRaisesRegex(ValueError, "filename"):
            proposed_pin(VERSION, BUILD, changed)

    def test_missing_size_and_sha_are_rejected(self):
        for key, value in (("size", 0), ("checksums", {})):
            with self.subTest(key=key):
                changed = metadata()
                changed["downloads"]["server:default"][key] = value
                with self.assertRaises(ValueError):
                    proposed_pin(VERSION, BUILD, changed)

    def test_bad_build_list_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not a list"):
            newest_stable_id({"id": BUILD})

    def test_wrong_project_is_rejected(self):
        changed = metadata()
        changed["project"] = "folia"
        with self.assertRaisesRegex(ValueError, "project/version"):
            proposed_pin(VERSION, BUILD, changed)


    def test_read_only_job_precedes_immutable_publisher(self):
        from pathlib import Path
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/build.yml").read_text()
        discovery = "\n  discover-stable-paper-26-3:\n"
        publisher = "\n  publish:\n"
        self.assertEqual(workflow.count(discovery), 1)
        self.assertEqual(workflow.count(publisher), 1)
        self.assertLess(workflow.index(discovery), workflow.index(publisher))
        self.assertNotRegex(workflow.split(publisher, 1)[1], r"\n  [a-z][a-z0-9-]*:\n")


if __name__ == "__main__":
    unittest.main()
