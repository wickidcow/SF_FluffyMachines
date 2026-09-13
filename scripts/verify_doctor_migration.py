#!/usr/bin/env python3
"""Verify Fluffy Machines' optional Slimefun Doctor migration bridge stays safe and compatible."""

from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []


def read(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        ERRORS.append(f"missing required file: {path}")
        return ""
    return file.read_text(encoding="utf-8")


def require(value: bool, message: str) -> None:
    if not value:
        ERRORS.append(message)


def reject(value: bool, message: str) -> None:
    if value:
        ERRORS.append(message)


bridge = read("src/main/java/io/ncbpfluffybear/fluffymachines/diagnostics/LegacyDoctorBridge.java")
probe = read("src/main/java/io/ncbpfluffybear/fluffymachines/diagnostics/LegacyDollySchemaProbe.java")
validator = read("src/main/java/io/ncbpfluffybear/fluffymachines/diagnostics/LegacyDollySchemaValidator.java")
migrator = read("src/main/java/io/ncbpfluffybear/fluffymachines/diagnostics/LegacyDollySchemaMigrator.java")
doctor = read("src/main/java/io/ncbpfluffybear/fluffymachines/diagnostics/FluffyDoctor.java")
plugin = read("src/main/java/io/ncbpfluffybear/fluffymachines/FluffyMachines.java")
bridge_compact = " ".join(bridge.split())

require('"io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaProbe"' in bridge,
        "reflective LegacyItemSchemaProbe binding is missing")
require('"io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaValidator"' in bridge,
        "reflective LegacyItemSchemaValidator binding is missing")
require('"io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaMigrator"' in bridge,
        "reflective LegacyItemSchemaMigrator binding is missing")
require('candidateClass.getConstructor( String.class, readinessClass, String.class, String.class)' in bridge_compact,
        "schema candidate validation-claim constructor compatibility is missing")
require('validationClass.getConstructor(statusClass, String.class, String.class)' in bridge_compact,
        "schema validation migration-payload constructor compatibility is missing")
require('case "getSupportedItemIds" -> Set.of("DOLLY")' in bridge,
        "Dolly schema probe must remain scoped to the DOLLY Slimefun ID")
require('case "getSupportedCandidateTypes" -> Set.of(LegacyDollySchemaProbe.CANDIDATE_TYPE)' in bridge,
        "validator/migrator must remain scoped to the Dolly candidate type")
require('arguments.length < 5' in bridge and 'LegacyDollySchemaMigrator.migrate(item, claim, payload)' in bridge,
        "reflective migrator must match the five-argument Slimefun Legacy contract")
require('Bukkit.getServicesManager().unregisterAll(plugin)' in bridge,
        "Doctor services must be unregistered cleanly on plugin disable")

require('CANDIDATE_TYPE = "legacy-dolly-backpack-binding"' in probe,
        "Dolly candidate type must remain stable")
require('PlayerBackpack.getBackpackUUID(meta).isPresent()' in probe,
        "already-modern Dolly bindings must be excluded from migration")
require('"VALIDATION_REQUIRED"' in probe and 'owner + "#" + backpackId' in probe,
        "legacy Dolly bindings must require backing-state validation")
require('"MANUAL_ONLY"' in probe,
        "malformed legacy-looking Dolly bindings must fail closed")

require('getBackpackAsync(' in validator,
        "Dolly validator must resolve the existing backing backpack")
require('parsed.owner().equals(backpack.getOwner().getUniqueId())' in validator,
        "Dolly validator must verify backing ownership")
require('parsed.backpackId() != backpack.getId()' in validator,
        "Dolly validator must verify the historical backpack number")
require('backpack.getUniqueId().toString()' in validator,
        "VERIFIED validation must carry the resolved modern backpack UUID as private payload")
require('"BACKING_DATA_MISSING"' in validator and '"STATE_MISMATCH"' in validator,
        "Dolly validator must distinguish missing and mismatched backing state")
reject('saveBackpack' in validator or 'saveBackpackInventory' in validator,
       "Dolly validation must remain read-only")

require('PlayerBackpack.setItemPdc(dolly, backpackUuid, claim.owner().toString())' in migrator,
        "Dolly migration must install the modern UUID/owner PDC binding")
require('PlayerBackpack.getBackpackUUID(meta).isPresent()' in migrator,
        "Dolly migrator must refuse an already-modern item")
require('isUuid(backpackUuid)' in migrator,
        "Dolly migrator must validate the private UUID payload before mutation")
reject('getBackpackAsync' in migrator or 'saveBackpack' in migrator or 'saveBackpackInventory' in migrator,
       "Dolly schema migrator must not read or rewrite backing backpack storage")
reject('setItemData(' in migrator,
       "same-ID Dolly migration must never rewrite the Slimefun item ID")

require('controller.getAllLoadedChunkData()' in doctor,
        "Fluffy barrel Doctor must stay limited to already-loaded Slimefun block data")
reject('loadChunk' in doctor or 'getChunkAtAsync' in doctor,
       "Fluffy barrel Doctor must never force-load chunks")
require('LegacyDoctorBridge.register(this)' in plugin,
        "Fluffy Machines must register the optional Doctor bridge on enable")
require('LegacyDoctorBridge.unregister(this)' in plugin,
        "Fluffy Machines must unregister Doctor services on disable")

if ERRORS:
    print("Fluffy Doctor migration verification failed:")
    for error in ERRORS:
        print(" -", error)
    raise SystemExit(1)

print("Fluffy Doctor migration verification passed.")
