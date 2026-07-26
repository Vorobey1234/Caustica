package dev.comfyfluffy.caustica.oidn;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Minimal FFM binding for the Open Image Denoise 2.x C API used by Caustica. */
public final class OidnLibrary {
    static final int DEVICE_TYPE_CPU = 1;
    static final int FORMAT_HALF3 = 259;
    public static final int QUALITY_FAST = 4;
    public static final int QUALITY_HIGH = 6;

    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle newDevice;
    private final MethodHandle commitDevice;
    private final MethodHandle releaseDevice;
    private final MethodHandle getDeviceError;
    private final MethodHandle newFilter;
    private final MethodHandle releaseFilter;
    private final MethodHandle setSharedFilterImage;
    private final MethodHandle setFilterBool;
    private final MethodHandle setFilterInt;
    private final MethodHandle commitFilter;
    private final MethodHandle executeFilter;

    OidnLibrary(SymbolLookup lookup) {
        newDevice = handle(lookup, "oidnNewDevice",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        commitDevice = handle(lookup, "oidnCommitDevice",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        releaseDevice = handle(lookup, "oidnReleaseDevice",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        getDeviceError = handle(lookup, "oidnGetDeviceError",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        newFilter = handle(lookup, "oidnNewFilter",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        releaseFilter = handle(lookup, "oidnReleaseFilter",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        setSharedFilterImage = handle(lookup, "oidnSetSharedFilterImage",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        setFilterBool = handle(lookup, "oidnSetFilterBool",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        setFilterInt = handle(lookup, "oidnSetFilterInt",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        commitFilter = handle(lookup, "oidnCommitFilter",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        executeFilter = handle(lookup, "oidnExecuteFilter",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("OpenImageDenoise missing export " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    public MemorySegment newCpuDevice() {
        try {
            return (MemorySegment) newDevice.invokeExact(DEVICE_TYPE_CPU);
        } catch (Throwable t) {
            throw failure("oidnNewDevice", t);
        }
    }

    public void commitDevice(MemorySegment device) {
        invokeVoid(commitDevice, "oidnCommitDevice", device);
    }

    public void releaseDevice(MemorySegment device) {
        invokeVoid(releaseDevice, "oidnReleaseDevice", device);
    }

    public int deviceError(MemorySegment device) {
        try {
            return (int) getDeviceError.invokeExact(device, MemorySegment.NULL);
        } catch (Throwable t) {
            throw failure("oidnGetDeviceError", t);
        }
    }

    public MemorySegment newRtFilter(MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) newFilter.invokeExact(device, arena.allocateFrom("RT"));
        } catch (Throwable t) {
            throw failure("oidnNewFilter", t);
        }
    }

    public void releaseFilter(MemorySegment filter) {
        invokeVoid(releaseFilter, "oidnReleaseFilter", filter);
    }

    public void setHalf3Image(MemorySegment filter, String name, long address, int width, int height) {
        try (Arena arena = Arena.ofConfined()) {
            setSharedFilterImage.invokeExact(filter, arena.allocateFrom(name), MemorySegment.ofAddress(address),
                    FORMAT_HALF3, (long) width, (long) height, 0L, 8L, (long) width * 8L);
        } catch (Throwable t) {
            throw failure("oidnSetSharedFilterImage(" + name + ")", t);
        }
    }

    public void setBool(MemorySegment filter, String name, boolean value) {
        try (Arena arena = Arena.ofConfined()) {
            setFilterBool.invokeExact(filter, arena.allocateFrom(name), value);
        } catch (Throwable t) {
            throw failure("oidnSetFilterBool(" + name + ")", t);
        }
    }

    public void setInt(MemorySegment filter, String name, int value) {
        try (Arena arena = Arena.ofConfined()) {
            setFilterInt.invokeExact(filter, arena.allocateFrom(name), value);
        } catch (Throwable t) {
            throw failure("oidnSetFilterInt(" + name + ")", t);
        }
    }

    public void commitFilter(MemorySegment filter) {
        invokeVoid(commitFilter, "oidnCommitFilter", filter);
    }

    public void executeFilter(MemorySegment filter) {
        invokeVoid(executeFilter, "oidnExecuteFilter", filter);
    }

    private static void invokeVoid(MethodHandle handle, String name, MemorySegment value) {
        try {
            handle.invokeExact(value);
        } catch (Throwable t) {
            throw failure(name, t);
        }
    }

    private static RuntimeException failure(String operation, Throwable cause) {
        return new RuntimeException(operation + " failed", cause);
    }
}
