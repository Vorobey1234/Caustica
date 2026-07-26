package dev.comfyfluffy.caustica.nrd;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Minimal FFM surface exported by the Caustica/NRI bridge. */
public final class NrdLibrary {
    private final MethodHandle create;
    private final MethodHandle denoise;
    private final MethodHandle destroy;
    private final MethodHandle getLastError;

    NrdLibrary(SymbolLookup lookup) {
        create = NativeHandles.handle(lookup, "causticaNrdCreate", FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        denoise = NativeHandles.handle(lookup, "causticaNrdDenoise", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        destroy = NativeHandles.handle(lookup, "causticaNrdDestroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        getLastError = NativeHandles.handle(lookup, "causticaNrdGetLastError",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
    }

    public MemorySegment create(long instance, long physicalDevice, long device,
                                int queueFamily, int width, int height) {
        try {
            return (MemorySegment) create.invokeExact(instance, physicalDevice, device, queueFamily, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("causticaNrdCreate failed", t);
        }
    }

    public boolean denoise(MemorySegment context, long commandBuffer,
                           long signal, long motion, long normalRoughness, long viewZ, long output,
                           MemorySegment projection, MemorySegment previousProjection,
                           MemorySegment view, MemorySegment previousView, int frameIndex, int method,
                           boolean reset) {
        try {
            int result = (int) denoise.invokeExact(context, commandBuffer, signal, motion, normalRoughness,
                    viewZ, output, projection, previousProjection, view, previousView,
                    frameIndex, method, reset ? 1 : 0);
            return result != 0;
        } catch (Throwable t) {
            throw new RuntimeException("causticaNrdDenoise failed", t);
        }
    }

    public void destroy(MemorySegment context) {
        try {
            destroy.invokeExact(context);
        } catch (Throwable t) {
            throw new RuntimeException("causticaNrdDestroy failed", t);
        }
    }

    public String lastError() {
        try {
            MemorySegment address = (MemorySegment) getLastError.invokeExact();
            return address.equals(MemorySegment.NULL)
                    ? "native bridge returned no error text"
                    : address.reinterpret(2048).getString(0);
        } catch (Throwable t) {
            return "could not read native NRD error: " + t;
        }
    }

    private static final class NativeHandles {
        static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            return java.lang.foreign.Linker.nativeLinker().downcallHandle(lookup.find(name)
                    .orElseThrow(() -> new IllegalStateException("NRD bridge missing export " + name)), descriptor);
        }
    }
}
