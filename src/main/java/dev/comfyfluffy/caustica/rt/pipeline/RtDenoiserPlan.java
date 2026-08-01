package dev.comfyfluffy.caustica.rt.pipeline;

/** Resolves denoiser options into one frame's fixed BMFR -> NRD -> OIDN chain. */
public record RtDenoiserPlan(boolean bmfr, boolean nrd, boolean oidnRealtime,
                             boolean oidnReferenceRequested, boolean oidnReferenceHeld,
                             boolean dlssRr) {
    public static RtDenoiserPlan create(boolean beautyView,
                                        boolean bmfrEnabled, boolean nrdEnabled,
                                        boolean oidnRealtimeEnabled, boolean oidnReferenceEnabled,
                                        boolean oidnReferenceHeld, boolean dlssRrEnabled) {
        boolean referenceRequested = beautyView && oidnReferenceEnabled;
        boolean referenceHeld = beautyView && !referenceRequested && oidnReferenceHeld;
        boolean reference = referenceRequested || referenceHeld;
        boolean bmfr = beautyView && !reference && bmfrEnabled;
        boolean nrd = beautyView && !reference && nrdEnabled;
        boolean oidnRealtime = beautyView && !reference && oidnRealtimeEnabled;
        boolean dlssRr = beautyView && !reference && !bmfr && !nrd && !oidnRealtime && dlssRrEnabled;
        return new RtDenoiserPlan(bmfr, nrd, oidnRealtime,
                referenceRequested, referenceHeld, dlssRr);
    }

    public boolean oidnReference() {
        return oidnReferenceRequested || oidnReferenceHeld;
    }

    public boolean anyRealtime() {
        return bmfr || nrd || oidnRealtime;
    }

    public int renderPercent(int selectedPercent) {
        return anyRealtime() ? Math.clamp(selectedPercent, 25, 100) : 100;
    }
}
