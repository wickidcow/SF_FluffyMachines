package io.ncbpfluffybear.fluffymachines.diagnostics;

import io.ncbpfluffybear.fluffymachines.FluffyMachines;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/** Reflective bridge to Slimefun Legacy's optional Doctor service APIs. */
public final class LegacyDoctorBridge {

    private static final String DOCTOR_API = "io.github.thebusybiscuit.slimefun4.api.diagnostics.AddonDoctor";
    private static final String REPORT_API = "io.github.thebusybiscuit.slimefun4.api.diagnostics.AddonDoctorReport";
    private static final String SCHEMA_PROBE_API =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaProbe";
    private static final String SCHEMA_CANDIDATE_API =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaCandidate";
    private static final String SCHEMA_READINESS_API =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaCandidate$Readiness";
    private static final String SCHEMA_VALIDATOR_API =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaValidator";
    private static final String SCHEMA_VALIDATION_API =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaValidation";
    private static final String SCHEMA_VALIDATION_STATUS_API =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaValidation$Status";
    private static final String SCHEMA_MIGRATOR_API =
            "io.github.thebusybiscuit.slimefun4.api.diagnostics.LegacyItemSchemaMigrator";

    private LegacyDoctorBridge() {}

    public static void register(FluffyMachines plugin) {
        Plugin slimefun = Bukkit.getPluginManager().getPlugin("Slimefun");
        if (slimefun == null) return;

        ClassLoader loader = slimefun.getClass().getClassLoader();
        registerAddonDoctor(plugin, loader);
        registerSchemaProbe(plugin, loader);
        registerSchemaValidator(plugin, loader);
        registerSchemaMigrator(plugin, loader);
    }

    private static void registerAddonDoctor(FluffyMachines plugin, ClassLoader loader) {
        try {
            Class<?> doctorInterface = Class.forName(DOCTOR_API, false, loader);
            Class<?> reportClass = Class.forName(REPORT_API, false, loader);
            Constructor<?> reportConstructor = reportClass.getConstructor(
                    String.class, boolean.class, long.class, long.class, long.class, long.class, List.class);
            InvocationHandler handler =
                    (proxy, method, arguments) -> invokeDoctor(proxy, method, arguments, reportConstructor);
            Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {doctorInterface}, handler);
            registerRaw(Bukkit.getServicesManager(), doctorInterface, provider, plugin);
            plugin.getLogger().info("Registered Fluffy Machines with Slimefun Legacy Addon Doctor.");
        } catch (ClassNotFoundException ignored) {
            // Other Slimefun implementations do not necessarily expose the optional Legacy API.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not register the optional Slimefun Doctor bridge.", exception);
        }
    }

    private static void registerSchemaProbe(FluffyMachines plugin, ClassLoader loader) {
        try {
            Class<?> probeInterface = Class.forName(SCHEMA_PROBE_API, false, loader);
            Class<?> candidateClass = Class.forName(SCHEMA_CANDIDATE_API, false, loader);
            Class<?> readinessClass = Class.forName(SCHEMA_READINESS_API, false, loader);
            Constructor<?> candidateConstructor;
            boolean supportsValidationClaim;
            try {
                candidateConstructor = candidateClass.getConstructor(
                        String.class, readinessClass, String.class, String.class);
                supportsValidationClaim = true;
            } catch (NoSuchMethodException ignored) {
                candidateConstructor = candidateClass.getConstructor(String.class, readinessClass, String.class);
                supportsValidationClaim = false;
            }
            Method readinessValueOf = readinessClass.getMethod("valueOf", String.class);
            Constructor<?> finalConstructor = candidateConstructor;
            boolean finalSupportsClaim = supportsValidationClaim;
            InvocationHandler handler = (proxy, method, arguments) -> invokeSchemaProbe(
                    proxy, method, arguments, finalConstructor, readinessValueOf, finalSupportsClaim);
            Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {probeInterface}, handler);
            registerRaw(Bukkit.getServicesManager(), probeInterface, provider, plugin);
            plugin.getLogger().info("Registered Fluffy Machines legacy item schema probe with Slimefun Doctor.");
        } catch (ClassNotFoundException ignored) {
            // Older Legacy, United and Gugu builds do not expose this optional migration API.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not register the optional Slimefun schema migration probe.", exception);
        }
    }

    private static void registerSchemaValidator(FluffyMachines plugin, ClassLoader loader) {
        try {
            Class<?> validatorInterface = Class.forName(SCHEMA_VALIDATOR_API, false, loader);
            Class<?> validationClass = Class.forName(SCHEMA_VALIDATION_API, false, loader);
            Class<?> statusClass = Class.forName(SCHEMA_VALIDATION_STATUS_API, false, loader);
            Constructor<?> validationConstructor;
            boolean supportsMigrationPayload;
            try {
                validationConstructor = validationClass.getConstructor(statusClass, String.class, String.class);
                supportsMigrationPayload = true;
            } catch (NoSuchMethodException ignored) {
                validationConstructor = validationClass.getConstructor(statusClass, String.class);
                supportsMigrationPayload = false;
            }
            Method statusValueOf = statusClass.getMethod("valueOf", String.class);
            Constructor<?> finalConstructor = validationConstructor;
            boolean finalSupportsPayload = supportsMigrationPayload;
            InvocationHandler handler = (proxy, method, arguments) -> invokeSchemaValidator(
                    proxy, method, arguments, finalConstructor, statusValueOf, finalSupportsPayload);
            Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {validatorInterface}, handler);
            registerRaw(Bukkit.getServicesManager(), validatorInterface, provider, plugin);
            plugin.getLogger().info("Registered Fluffy Machines Dolly backing-state validator with Slimefun Doctor.");
        } catch (ClassNotFoundException ignored) {
            // Validation API is optional and newer than the basic schema probe contract.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not register the optional Slimefun schema validator.", exception);
        }
    }

    private static void registerSchemaMigrator(FluffyMachines plugin, ClassLoader loader) {
        try {
            Class<?> migratorInterface = Class.forName(SCHEMA_MIGRATOR_API, false, loader);
            InvocationHandler handler = LegacyDoctorBridge::invokeSchemaMigrator;
            Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {migratorInterface}, handler);
            registerRaw(Bukkit.getServicesManager(), migratorInterface, provider, plugin);
            plugin.getLogger().info("Registered Fluffy Machines Dolly schema migrator with Slimefun Doctor.");
        } catch (ClassNotFoundException ignored) {
            // Schema mutation is optional and only available on newer Slimefun Legacy builds.
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING, "Could not register the optional Slimefun schema migrator.", exception);
        }
    }

    public static void unregister(FluffyMachines plugin) {
        Bukkit.getServicesManager().unregisterAll(plugin);
    }

    private static Object invokeDoctor(Object proxy, Method method, Object[] arguments, Constructor<?> reportConstructor)
            throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "getAddonName" -> "Fluffy Machines";
            case "runDoctor" -> {
                boolean repair = arguments != null && arguments.length > 0 && Boolean.TRUE.equals(arguments[0]);
                FluffyDoctorReport report = FluffyDoctor.run(repair);
                yield reportConstructor.newInstance(
                        "Fluffy Machines", repair, report.getScannedEntries(), report.getIssuesFound(),
                        report.getRepairedEntries(), report.getFailures(), report.getDetails());
            }
            case "toString" -> "FluffyMachinesAddonDoctor";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
            default -> throw new UnsupportedOperationException("Unsupported AddonDoctor method: " + method.getName());
        };
    }

    private static Object invokeSchemaProbe(
            Object proxy,
            Method method,
            Object[] arguments,
            Constructor<?> candidateConstructor,
            Method readinessValueOf,
            boolean supportsValidationClaim)
            throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "getMigrationName" -> "Fluffy Machines legacy item schemas";
            case "getSupportedItemIds" -> Set.of("DOLLY");
            case "probeItem" -> {
                if (arguments == null
                        || arguments.length < 2
                        || !(arguments[0] instanceof ItemStack item)
                        || !"DOLLY".equals(arguments[1])) {
                    yield null;
                }
                LegacyDollySchemaProbe.Result result = LegacyDollySchemaProbe.inspect(item);
                if (result == null) yield null;
                Object readiness = readinessValueOf.invoke(null, result.readiness());
                if (supportsValidationClaim) {
                    yield candidateConstructor.newInstance(
                            result.candidateType(), readiness, result.detail(), result.validationClaim());
                }
                if (result.validationClaim() != null) {
                    Object manual = readinessValueOf.invoke(null, "MANUAL_ONLY");
                    yield candidateConstructor.newInstance(
                            result.candidateType(), manual,
                            "Legacy Dolly binding detected; this Legacy build cannot validate it automatically.");
                }
                yield candidateConstructor.newInstance(result.candidateType(), readiness, result.detail());
            }
            case "toString" -> "FluffyMachinesLegacyItemSchemaProbe";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
            default -> throw new UnsupportedOperationException(
                    "Unsupported LegacyItemSchemaProbe method: " + method.getName());
        };
    }

    private static Object invokeSchemaValidator(
            Object proxy,
            Method method,
            Object[] arguments,
            Constructor<?> validationConstructor,
            Method statusValueOf,
            boolean supportsMigrationPayload)
            throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "getSupportedCandidateTypes" -> Set.of(LegacyDollySchemaProbe.CANDIDATE_TYPE);
            case "validateCandidate" -> {
                if (arguments == null
                        || arguments.length < 2
                        || !LegacyDollySchemaProbe.CANDIDATE_TYPE.equals(arguments[0])
                        || !(arguments[1] instanceof String claim)) {
                    yield java.util.concurrent.CompletableFuture.completedFuture(null);
                }
                yield LegacyDollySchemaValidator.validate(claim).thenApply(result -> {
                    try {
                        Object status = statusValueOf.invoke(null, result.status());
                        if (supportsMigrationPayload) {
                            return validationConstructor.newInstance(status, result.detail(), result.migrationPayload());
                        }
                        return validationConstructor.newInstance(status, result.detail());
                    } catch (ReflectiveOperationException exception) {
                        throw new IllegalStateException("Could not create Slimefun schema validation result", exception);
                    }
                });
            }
            case "toString" -> "FluffyMachinesLegacyItemSchemaValidator";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
            default -> throw new UnsupportedOperationException(
                    "Unsupported LegacyItemSchemaValidator method: " + method.getName());
        };
    }

    private static Object invokeSchemaMigrator(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "getSupportedCandidateTypes" -> Set.of(LegacyDollySchemaProbe.CANDIDATE_TYPE);
            case "migrateItem" -> {
                if (arguments == null
                        || arguments.length < 5
                        || !(arguments[0] instanceof ItemStack item)
                        || !"DOLLY".equals(arguments[1])
                        || !LegacyDollySchemaProbe.CANDIDATE_TYPE.equals(arguments[2])
                        || !(arguments[3] instanceof String claim)
                        || !(arguments[4] instanceof String payload)) {
                    yield false;
                }
                yield LegacyDollySchemaMigrator.migrate(item, claim, payload);
            }
            case "toString" -> "FluffyMachinesLegacyItemSchemaMigrator";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
            default -> throw new UnsupportedOperationException(
                    "Unsupported LegacyItemSchemaMigrator method: " + method.getName());
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerRaw(ServicesManager services, Class service, Object provider, FluffyMachines plugin) {
        services.register(service, provider, plugin, ServicePriority.Normal);
    }
}
