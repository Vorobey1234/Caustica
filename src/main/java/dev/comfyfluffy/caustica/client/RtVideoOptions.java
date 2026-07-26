package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            rayBudget(),
            rayBudgetJitter(),
            maxBounces(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            temporalDenoiser(),
            spatialDenoiser(),
            bmfrDenoiser(),
            nrdDenoiser(),
            nrdMethod(),
            oidnRealtimeDenoiser(),
            realtimeDenoiserResolution(),
            accumulationMode(),
            dlssQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            debugView(),
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static final List<Integer> RAY_BUDGETS = List.of(1, 2, 4, 8, 16);

    private static OptionInstance<Integer> rayBudget() {
        IntSetting setting = CausticaConfig.Rt.Composite.RAY_BUDGET_DIVISOR;
        int divisor = RAY_BUDGETS.contains(setting.value()) ? setting.value() : 1;
        return new OptionInstance<>(
                "caustica.options.rt.rayBudget",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.rayBudget.tooltip")),
                (caption, position) -> Options.genericValueLabel(caption,
                        Component.translatable("caustica.options.rt.rayBudget." + RAY_BUDGETS.get(position))),
                new OptionInstance.IntRange(0, RAY_BUDGETS.size() - 1),
                RAY_BUDGETS.indexOf(divisor),
                position -> setting.set(RAY_BUDGETS.get(position)));
    }

    private static OptionInstance<Boolean> rayBudgetJitter() {
        return bool("caustica.options.rt.rayBudgetJitter",
                CausticaConfig.Rt.Composite.RAY_BUDGET_JITTER);
    }

    private static final List<Integer> PATH_BOUNCES = List.of(2, 4, 8, 16, 32, 0);

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        int selected = PATH_BOUNCES.contains(setting.value()) ? setting.value() : 4;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    PATH_BOUNCES.get(position) == 0
                            ? Component.translatable("caustica.options.rt.maxBounces.infinite")
                            : Component.literal(Integer.toString(PATH_BOUNCES.get(position)))),
            new OptionInstance.IntRange(0, PATH_BOUNCES.size() - 1),
            PATH_BOUNCES.indexOf(selected),
            position -> setting.set(PATH_BOUNCES.get(position)));
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Boolean> temporalDenoiser() {
        return bool("caustica.options.rt.temporalDenoiser", CausticaConfig.Rt.Denoiser.TEMPORAL_ENABLED);
    }

    private static OptionInstance<Boolean> spatialDenoiser() {
        return bool("caustica.options.rt.spatialDenoiser", CausticaConfig.Rt.Denoiser.SPATIAL_ENABLED);
    }

    private static OptionInstance<Boolean> oidnRealtimeDenoiser() {
        return OptionInstance.createBoolean(
                "caustica.options.rt.oidnRealtimeDenoiser",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "caustica.options.rt.oidnRealtimeDenoiser.tooltip")),
                CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.value(),
                enabled -> {
                    CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.set(enabled);
                    if (enabled) {
                        CausticaConfig.Rt.Denoiser.OIDN_ENABLED.set(false);
                        CausticaConfig.Rt.Denoiser.NRD_ENABLED.set(false);
                        CausticaConfig.Rt.Denoiser.BMFR_ENABLED.set(false);
                    }
                });
    }

    private static OptionInstance<Boolean> bmfrDenoiser() {
        return OptionInstance.createBoolean(
                "caustica.options.rt.bmfrDenoiser",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "caustica.options.rt.bmfrDenoiser.tooltip")),
                CausticaConfig.Rt.Denoiser.BMFR_ENABLED.value(),
                enabled -> {
                    CausticaConfig.Rt.Denoiser.BMFR_ENABLED.set(enabled);
                    if (enabled) {
                        CausticaConfig.Rt.Denoiser.NRD_ENABLED.set(false);
                        CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.set(false);
                        CausticaConfig.Rt.Denoiser.OIDN_ENABLED.set(false);
                    }
                });
    }

    private static OptionInstance<Boolean> nrdDenoiser() {
        return OptionInstance.createBoolean(
                "caustica.options.rt.nrdDenoiser",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.nrdDenoiser.tooltip")),
                CausticaConfig.Rt.Denoiser.NRD_ENABLED.value(),
                enabled -> {
                    CausticaConfig.Rt.Denoiser.NRD_ENABLED.set(enabled);
                    if (enabled) {
                        CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.set(false);
                        CausticaConfig.Rt.Denoiser.OIDN_ENABLED.set(false);
                        CausticaConfig.Rt.Denoiser.BMFR_ENABLED.set(false);
                    }
                });
    }

    private static OptionInstance<String> nrdMethod() {
        StringSetting setting = CausticaConfig.Rt.Denoiser.NRD_METHOD;
        return new OptionInstance<>(
                "caustica.options.rt.nrdMethod",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.nrdMethod.tooltip")),
                (caption, value) -> Component.translatable("caustica.options.rt.nrdMethod." + value),
                new OptionInstance.Enum<>(List.of("relax", "reblur", "reference"), Codec.STRING),
                setting.get(),
                setting::set);
    }

    private static OptionInstance<Integer> realtimeDenoiserResolution() {
        IntSetting setting = CausticaConfig.Rt.Denoiser.REALTIME_RESOLUTION_PERCENT;
        return new OptionInstance<>(
                "caustica.options.rt.realtimeDenoiserResolution",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "caustica.options.rt.realtimeDenoiserResolution.tooltip")),
                (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
                new OptionInstance.IntRange(25, 100),
                setting.value(),
                setting::set);
    }

    /** One-shot reference capture; unlike an option toggle this cannot accidentally run every frame. */
    public static Button oidnReferenceButton() {
        return Button.builder(Component.translatable("caustica.options.rt.oidnReferenceRun"), button -> {
            CausticaConfig.Rt.Denoiser.NRD_ENABLED.set(false);
            CausticaConfig.Rt.Denoiser.BMFR_ENABLED.set(false);
            CausticaConfig.Rt.Denoiser.OIDN_REALTIME_ENABLED.set(false);
            CausticaConfig.Rt.Denoiser.OIDN_ENABLED.set(true);
        }).width(310).build();
    }


    private static OptionInstance<Boolean> accumulationMode() {
        return bool("caustica.options.rt.accumulation", CausticaConfig.Rt.Composite.ACCUMULATION_ENABLED);
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Codec.INT),
            Math.clamp(setting.value(), 0, 9),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }

}
