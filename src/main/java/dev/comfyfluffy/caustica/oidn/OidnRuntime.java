package dev.comfyfluffy.caustica.oidn;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/** Loads the bundled Windows/x64 OIDN CPU runtime and owns its device. */
public final class OidnRuntime {
    public static final OidnRuntime INSTANCE = new OidnRuntime();

    /** Files shipped by the OIDN Windows package. The tbbbind variants are optional helpers. */
    private static final List<String> RUNTIME_FILES = List.of(
            "tbb12.dll", "tbbbind.dll", "tbbbind_2_0.dll", "tbbbind_2_5.dll",
            "OpenImageDenoise_core.dll", "OpenImageDenoise_device_cpu.dll", "OpenImageDenoise.dll");
    /** Only hard dependencies are preloaded. Loading tbbbind_2_0 aborts on normal OIDN installs. */
    private static final List<String> PRELOAD_ORDER = List.of(
            "tbb12.dll", "OpenImageDenoise_core.dll",
            "OpenImageDenoise_device_cpu.dll", "OpenImageDenoise.dll");
    private static final String RESOURCE_ROOT = "/caustica/natives/windows-x64/oidn/";

    private OidnLibrary library;
    private MemorySegment device = MemorySegment.NULL;
    private boolean failed;

    private OidnRuntime() {
    }

    public synchronized Session acquire() {
        if (failed) {
            return null;
        }
        if (library != null && !device.equals(MemorySegment.NULL)) {
            return new Session(library, device);
        }
        try {
            Path main = locateRuntime();
            Path dir = main.getParent();
            // Preload only direct dependencies from one directory. tbbbind*.dll are optional and
            // may themselves reference libraries that are intentionally absent from this package.
            for (String name : PRELOAD_ORDER) {
                Path dll = dir.resolve(name);
                if (!Files.isRegularFile(dll)) {
                    throw new IOException("missing OIDN runtime dependency " + dll);
                }
                System.load(dll.toAbsolutePath().toString());
            }
            library = new OidnLibrary(SymbolLookup.loaderLookup());
            device = library.newCpuDevice();
            requireHandle(device, "OIDN CPU device");
            library.commitDevice(device);
            check("oidnCommitDevice");
            CausticaMod.LOGGER.info("Open Image Denoise CPU backend initialized from {}", dir);
            return new Session(library, device);
        } catch (Throwable t) {
            failed = true;
            library = null;
            device = MemorySegment.NULL;
            CausticaMod.LOGGER.error("Open Image Denoise unavailable; using the built-in denoiser", t);
            return null;
        }
    }

    public synchronized void check(String operation) {
        if (library == null || device.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(operation + ": OIDN device is not initialized");
        }
        int error = library.deviceError(device);
        if (error != 0) {
            throw new IllegalStateException(operation + " failed with OIDN error " + error);
        }
    }

    public synchronized void shutdown() {
        if (library != null && !device.equals(MemorySegment.NULL)) {
            library.releaseDevice(device);
        }
        library = null;
        device = MemorySegment.NULL;
        failed = false;
    }

    private static Path locateRuntime() throws IOException {
        String override = System.getProperty("caustica.oidn.path");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override).toAbsolutePath().normalize();
            Path main = Files.isDirectory(path) ? path.resolve("OpenImageDenoise.dll") : path;
            if (Files.isDirectory(path) && !Files.isRegularFile(main)) {
                main = path.resolve("bin").resolve("OpenImageDenoise.dll");
            }
            if (Files.isRegularFile(main)) {
                return main;
            }
            throw new IOException("OpenImageDenoise.dll not found at " + main);
        }
        if (!isWindowsX64()) {
            throw new IOException("bundled OIDN runtime is Windows/x64 only");
        }
        Path dir = FabricLoader.getInstance().getGameDir().resolve("caustica-natives").resolve("oidn-2.5.0");
        Files.createDirectories(dir);
        for (String name : RUNTIME_FILES) {
            try (InputStream in = OidnRuntime.class.getResourceAsStream(RESOURCE_ROOT + name)) {
                if (in == null) {
                    throw new IOException("missing bundled OIDN native " + name);
                }
                Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return dir.resolve("OpenImageDenoise.dll");
    }

    private static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    private static void requireHandle(MemorySegment handle, String label) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException(label + " creation returned null");
        }
    }

    public record Session(OidnLibrary library, MemorySegment device) {
    }
}
