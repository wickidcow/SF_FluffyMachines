package io.ncbpfluffybear.fluffymachines.diagnostics;

import io.ncbpfluffybear.fluffymachines.FluffyMachines;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/** Reflective bridge to Slimefun Legacy's optional AddonDoctor service API. */
public final class LegacyDoctorBridge {

    private static final String DOCTOR_API = "io.github.thebusybiscuit.slimefun4.api.diagnostics.AddonDoctor";
    private static final String REPORT_API = "io.github.thebusybiscuit.slimefun4.api.diagnostics.AddonDoctorReport";

    private LegacyDoctorBridge() {
    }

    public static void register(FluffyMachines plugin) {
        Plugin slimefun = Bukkit.getPluginManager().getPlugin("Slimefun");
        if (slimefun == null) {
            return;
        }

        try {
            ClassLoader loader = slimefun.getClass().getClassLoader();
            Class<?> doctorInterface = Class.forName(DOCTOR_API, false, loader);
            Class<?> reportClass = Class.forName(REPORT_API, false, loader);
            Constructor<?> reportConstructor = reportClass.getConstructor(
                    String.class,
                    boolean.class,
                    long.class,
                    long.class,
                    long.class,
                    long.class,
                    List.class);

            InvocationHandler handler =
                    (proxy, method, arguments) -> invoke(proxy, method, arguments, reportConstructor);
            Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {doctorInterface}, handler);
            registerRaw(Bukkit.getServicesManager(), doctorInterface, provider, plugin);
            plugin.getLogger().info("Registered Fluffy Machines with Slimefun Legacy Addon Doctor.");
        } catch (ClassNotFoundException ignored) {
            // Other Slimefun implementations do not necessarily expose the optional Legacy API.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not register the optional Slimefun Doctor bridge.", exception);
        }
    }

    public static void unregister(FluffyMachines plugin) {
        Bukkit.getServicesManager().unregisterAll(plugin);
    }

    private static Object invoke(Object proxy, Method method, Object[] arguments, Constructor<?> reportConstructor)
            throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "getAddonName" -> "Fluffy Machines";
            case "runDoctor" -> {
                boolean repair = arguments != null && arguments.length > 0 && Boolean.TRUE.equals(arguments[0]);
                FluffyDoctorReport report = FluffyDoctor.run(repair);
                yield reportConstructor.newInstance(
                        "Fluffy Machines",
                        repair,
                        report.getScannedEntries(),
                        report.getIssuesFound(),
                        report.getRepairedEntries(),
                        report.getFailures(),
                        report.getDetails());
            }
            case "toString" -> "FluffyMachinesAddonDoctor";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
            default -> throw new UnsupportedOperationException("Unsupported AddonDoctor method: " + method.getName());
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerRaw(ServicesManager services, Class service, Object provider, FluffyMachines plugin) {
        services.register(service, provider, plugin, ServicePriority.Normal);
    }
}
