#!/usr/bin/env python3
"""Guard the native restart probe against premature menu reads and weakened controls."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / "scripts/runtime/src/audit/BackpackTransferLifecycleProbe.java"
RUNNER = ROOT / "scripts/runtime/run_backpack_transfer_probe.py"


def read_checks_java(source: str) -> str:
    method = "private CompletableFuture<Void> readChecks()"
    if source.count(method) != 1:
        raise ValueError("Exactly one native restart read phase must exist")
    return source.split(method, 1)[1].split("private void remember(", 1)[0]


def verify_guard(source: str, runner: str) -> None:
    read = read_checks_java(source)
    if "if (fixtures == null || fixtures.size() != 4)" not in read:
        raise ValueError("All four restart fixtures must remain mandatory")
    required = (
        "waitUntil(() -> data.isDataLoaded() && data.getBlockMenu() != null, 100)",
        "BlockMenu menu = data.getBlockMenu();",
        "if (!data.isDataLoaded() || menu == null)",
        "menu.getContents(), \"machine contents survive a separate process\"",
        "backpack.getInventory().getContents(), \"backpack contents survive a separate process\"",
        "remaining machine energy survives restart",
        "machine identity retained",
    )
    for contract in required:
        if contract not in read:
            raise ValueError(f"Native restart evidence guard absent: {contract}")
    if "data.getBlockMenu().getContents()" in read:
        raise ValueError("Unconditional nullable inventory dereference remains")
    if "setBlockMenu(" in read or "createBlock(" in read or "new BlockMenu(" in read:
        raise ValueError("Native evidence must use the real reconstructed persisted inventory")
    checks = (
        "EXPECTED_RESTART_CASES =",
        "set(restart_names) == EXPECTED_RESTART_CASES",
        "len(restart_names) == len(EXPECTED_RESTART_CASES)",
        'manifest["expected_case_set_present"] = case_set_ok',
        'infrastructure_ok = not transfer.get("fatal") and not restart.get("fatal") and restart.get("passed") and case_set_ok and log_ok',
    )
    for contract in checks:
        if contract not in runner:
            raise ValueError(f"Runner weakened its restart case coverage: {contract}")


class NativeRestartGuardContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = PROBE.read_text(encoding="utf-8")
        cls.runner = RUNNER.read_text(encoding="utf-8")

    def test_guarded_native_read_keeps_all_four_live_restart_cases(self):
        verify_guard(self.java, self.runner)

    def test_removed_async_readiness_wait_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_guard(self.java.replace(
                "waitUntil(() -> data.isDataLoaded() && data.getBlockMenu() != null, 100)",
                "waitUntil(() -> true, 100)"), self.runner)

    def test_shortened_restart_case_set_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_guard(self.java, self.runner.replace(
                "set(restart_names) == EXPECTED_RESTART_CASES", "set(restart_names).issubset(EXPECTED_RESTART_CASES)"))

    def test_reinstated_unsafe_nullable_menu_dereference_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_guard(self.java.replace("menu.getContents(), \"machine contents survive a separate process\"",
                                           "data.getBlockMenu().getContents(), \"machine contents survive a separate process\""),
                         self.runner)

    def test_fake_inventory_insertion_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_guard(self.java.replace(
                "BlockMenu menu = data.getBlockMenu();",
                "BlockMenu menu = data.getBlockMenu(); new BlockMenu(fakePreset, location, contents);"),
                self.runner)


if __name__ == "__main__":
    unittest.main()
