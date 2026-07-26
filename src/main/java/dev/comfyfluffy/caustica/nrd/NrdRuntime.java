package dev.comfyfluffy.caustica.nrd;

import dev.comfyfluffy.caustica.CausticaMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/** Extracts and loads the Windows/x64 NRD + NRI runtime in dependency order. */
public final class NrdRuntime {
    public static final NrdRuntime INSTANCE = new NrdRuntime();
    private static final List<String> DLLS = List.of("NRI.dll", "NRD.dll", "caustica_nrd.dll");
    private static final String RESOURCE_ROOT = "/caustica/natives/windows-x64/nrd/";
    private NrdLibrary library;
    private boolean failed;

    private NrdRuntime() {
    }

    public synchronized NrdLibrary acquire() {
        if (failed) return null;
        if (library != null) return library;
        try {
            if (!isWindowsX64()) throw new IOException("bundled NRD runtime is Windows/x64 only");
            String override = System.getProperty("caustica.nrd.path");
            Path dir;
            if (override != null && !override.isBlank()) {
                dir = Path.of(override).toAbsolutePath().normalize();
                for (String name : DLLS) {
                    if (!Files.isRegularFile(dir.resolve(name))) {
                        throw new IOException("missing NRD native " + dir.resolve(name));
                    }
                }
            } else {
                dir = FabricLoader.getInstance().getGameDir().resolve("caustica-natives").resolve("nrd-4.17.3");
                Files.createDirectories(dir);
                for (String name : DLLS) {
                    try (InputStream in = NrdRuntime.class.getResourceAsStream(RESOURCE_ROOT + name)) {
                        if (in == null) throw new IOException("missing bundled NRD native " + name);
                        Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            for (String name : DLLS) System.load(dir.resolve(name).toAbsolutePath().toString());
            library = new NrdLibrary(SymbolLookup.loaderLookup());
            CausticaMod.LOGGER.info("NVIDIA NRD 4.17.3 Vulkan backend loaded from {}", dir);
            return library;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("NVIDIA NRD unavailable; using another denoiser", t);
            return null;
        }
    }

    private static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
    }
}
