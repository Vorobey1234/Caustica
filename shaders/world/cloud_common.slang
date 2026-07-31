// Shared procedural cloud field for sky integration and surface/fog shadows. All functions are pure:
// raygen and miss shaders receive exactly the same weather, climate and absolute world origin.
module cloud_common;

public struct CloudParams {
    public float4 weather;     // x rain, y thunder, z overcast, w pause-aware game time in seconds
    public float2 climate;     // x biome temperature 0..1, y precipitation/moisture 0..1
    public float2 worldOrigin; // absolute terrain-rebase origin; never wrapped like the water-wave anchor
};

public static const float CLOUD_MIN_Y = 68.0;
public static const float CLOUD_MAX_Y = 384.0;
public static const float CLOUD_MAX_DISTANCE = 3200.0;
public static const float CLOUD_SIGMA_T = 0.032;

// Arithmetic hash: much cheaper and more temporally consistent than transcendental sin hashes.
public float cloudHash21(float2 p) {
    float3 p3 = frac(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

public float cloudNoise2(float2 p) {
    float2 i = floor(p);
    float2 f = frac(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = cloudHash21(i);
    float b = cloudHash21(i + float2(1.0, 0.0));
    float c = cloudHash21(i + float2(0.0, 1.0));
    float d = cloudHash21(i + float2(1.0, 1.0));
    return lerp(lerp(a, b, f.x), lerp(c, d, f.x), f.y);
}

public float cloudFbm2(float2 p) {
    float value = 0.0;
    float weight = 0.55;
    for (int octave = 0; octave < 3; ++octave) {
        value += weight * cloudNoise2(p);
        p = p * 2.03 + float2(19.7, -7.3);
        weight *= 0.53;
    }
    return value / 0.995425;
}

// Trilinear value noise in absolute world space.  The old cloud body was assembled exclusively from
// horizontal 2-D slices whose height-dependent warps remained visibly correlated.  A coarse ray march
// then projected those slices into camera-centred arcs.  This genuine 3-D field gives every sample an
// independent vertical coordinate while remaining deterministic under camera and terrain rebasing.
public float cloudHash31(float3 p) {
    p = frac(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return frac((p.x + p.y) * p.z);
}

public float cloudNoise3(float3 p) {
    float3 i = floor(p);
    float3 f = frac(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = cloudHash31(i);
    float n100 = cloudHash31(i + float3(1.0, 0.0, 0.0));
    float n010 = cloudHash31(i + float3(0.0, 1.0, 0.0));
    float n110 = cloudHash31(i + float3(1.0, 1.0, 0.0));
    float n001 = cloudHash31(i + float3(0.0, 0.0, 1.0));
    float n101 = cloudHash31(i + float3(1.0, 0.0, 1.0));
    float n011 = cloudHash31(i + float3(0.0, 1.0, 1.0));
    float n111 = cloudHash31(i + float3(1.0, 1.0, 1.0));
    float nx00 = lerp(n000, n100, f.x);
    float nx10 = lerp(n010, n110, f.x);
    float nx01 = lerp(n001, n101, f.x);
    float nx11 = lerp(n011, n111, f.x);
    return lerp(lerp(nx00, nx10, f.y), lerp(nx01, nx11, f.y), f.z);
}

public float cloudFbm3(float3 p) {
    float value = 0.64 * cloudNoise3(p);
    p = p * 2.03 + float3(19.7, -7.3, 11.9);
    value += 0.36 * cloudNoise3(p);
    return value;
}

public float2 cloudWindWorld(float timeSeconds) {
    // Rigid world-space advection: all octaves move together, so clouds travel instead of boiling.
    return float2(-0.18, 0.09) * timeSeconds;
}

public float2 cloudAbsoluteXZ(float3 rebasedPosition, CloudParams params) {
    return rebasedPosition.xz + params.worldOrigin - cloudWindWorld(params.weather.w);
}

// Low-cost scene atmosphere shared by the miss shader, cloud lighting and geometry fog.  Keeping one
// source palette is more important than independently tuning three plausible-looking gradients: a
// distant surface must converge to the exact same colour that is visible behind it.  The whole result
// is daylight-gated, so clear nights remain truly black; storms darken that same atmosphere rather than
// replacing it with an unrelated grey layer.
public float3 sharedSkyColor(float3 rayDir, float3 sunDir, float day,
                             float rain, float thunder, float overcast) {
    float daylight = saturate(day);
    if (daylight <= 1.0e-4) return float3(0.0, 0.0, 0.0);

    float up = saturate(rayDir.y);
    float horizonWeight = 1.0 - smoothstep(0.0, 0.82, up);
    float sunHeight = saturate(sunDir.y * 2.2 + 0.08);
    float3 clearZenith = lerp(float3(0.085, 0.155, 0.285),
            float3(0.155, 0.355, 0.650), sunHeight);
    float3 clearHorizon = lerp(float3(0.42, 0.19, 0.095),
            float3(0.58, 0.70, 0.84), sunHeight);
    float3 clearSky = lerp(clearZenith, clearHorizon, horizonWeight);

    float sunset = (1.0 - smoothstep(0.05, 0.34, max(sunDir.y, 0.0)))
            * pow(saturate(dot(normalize(rayDir), normalize(sunDir))), 10.0);
    clearSky += float3(0.48, 0.16, 0.045) * sunset;

    float storm = saturate(max(overcast, max(rain, thunder * 0.90)));
    float3 stormZenith = lerp(float3(0.055, 0.068, 0.090),
            float3(0.018, 0.022, 0.030), thunder);
    float3 stormHorizon = lerp(float3(0.090, 0.105, 0.130),
            float3(0.032, 0.037, 0.047), thunder);
    float3 stormSky = lerp(stormZenith, stormHorizon, horizonWeight);
    float weatherBlend = smoothstep(0.04, 0.96, storm);
    return max(lerp(clearSky, stormSky, weatherBlend) * daylight,
            float3(0.0, 0.0, 0.0));
}

// The storm deck must be shared by the miss shader, cloud volume and ground fog.  These values are
// emitted HDR radiance, not display colours: using a brighter hand-authored "rain ambient" for one
// of the three paths makes that layer turn into a white slab against the dark storm sky.
public float3 stormDeckRadiance(float3 rayDir, float rain, float thunder) {
    float horizon = 1.0 - saturate(abs(rayDir.y));
    float storm = max(saturate(rain), saturate(thunder));
    float darkening = 1.0 - 0.82 * storm;
    return lerp(float3(0.008, 0.010, 0.014), float3(0.028, 0.034, 0.045), horizon) * darkening;
}

public float cloudDensity(float3 p, CloudParams params) {
    float2 q = cloudAbsoluteXZ(p, params);
    float rain = saturate(params.weather.x);
    float overcast = saturate(params.weather.z);
    float storm = max(rain, overcast);
    float temperature = saturate(params.climate.x);
    float moisture = saturate(params.climate.y);

    // Regional archetype changes over kilometre-scale distances. It controls cloud height without
    // attaching the actual cloud coordinates to the player.
    float region = cloudNoise2(q * 0.00046 + float2(73.1, -41.7));
    float convectiveClimate = moisture * (0.58 + 0.42 * temperature);
    float tower = saturate(0.38 + 0.48 * region + 0.38 * convectiveClimate - 0.22 * storm);

    // Low, level bottoms and much taller tops produce recognisable cumulus rather than a thin sheet.
    float base = lerp(126.0, 88.0, storm) + lerp(8.0, -7.0, moisture);
    float top = base + lerp(128.0, 238.0, tower);
    float h = saturate((p.y - base) / max(top - base, 1.0));
    float profile = smoothstep(0.0, 0.065, h) * (1.0 - smoothstep(0.72, 1.0, h));

    // A broad world-space mask separates the sky into individual cloud families. Clear weather keeps
    // generous blue gaps; rain smoothly joins those families into a wider storm deck.
    float clusterNoise = cloudFbm2(q * 0.00072 + float2(-13.7, 29.4));
    float clusterThreshold = lerp(0.54, 0.39, overcast) + (1.0 - moisture) * 0.035;
    float cluster = smoothstep(clusterThreshold - 0.055, clusterThreshold + 0.085, clusterNoise);

    // A broad horizontal warp groups the genuinely 3-D body into large, coherent cloud families.
    float2 broadWarp = (float2(cloudNoise2(q * 0.00105 + 17.0),
            cloudNoise2(q * 0.00105 + 91.0)) - 0.5) * 82.0;
    float2 shaped = q + broadWarp;
    float3 volumePos = float3(shaped.x, p.y, shaped.y);

    // Large rounded 3-D bodies dominate; smaller 3-D scales add cauliflower lobes and erosion without
    // reintroducing a stack of height slices.
    float horizontalScale = lerp(0.00175, 0.00132, tower);
    float macro = cloudFbm3(volumePos * float3(horizontalScale, 0.0048, horizontalScale)
            + float3(0.0, h * 0.37, 0.0));
    float puffs = cloudNoise3(volumePos * float3(0.0046, 0.0115, 0.0046)
            + float3(13.1, -7.7, 29.4));
    float detail = cloudNoise3(volumePos * float3(0.0092, 0.0220, 0.0092)
            + float3(-5.3, 17.9, 8.6));

    float coverage = lerp(0.60, 0.43, overcast) - moisture * 0.035;
    // Narrow only the very top, leaving a deep vertical body below it.
    coverage += smoothstep(0.58, 1.0, h) * 0.075;
    float field = macro * 0.68 + puffs * 0.24 + detail * 0.08 + profile * 0.22 - coverage;
    float boundary = smoothstep(-0.085, 0.12, field);
    field -= (1.0 - boundary) * (detail - 0.5) * 0.045;
    return cluster * profile * smoothstep(-0.035, 0.095, field);
}

public bool cloudInterval(float3 rayOrigin, float3 rayDir, out float t0, out float t1) {
    if (abs(rayDir.y) < 1.0e-5) {
        if (rayOrigin.y < CLOUD_MIN_Y || rayOrigin.y > CLOUD_MAX_Y) {
            t0 = 0.0;
            t1 = 0.0;
            return false;
        }
        t0 = 0.0;
        t1 = CLOUD_MAX_DISTANCE;
        return true;
    }
    float a = (CLOUD_MIN_Y - rayOrigin.y) / rayDir.y;
    float b = (CLOUD_MAX_Y - rayOrigin.y) / rayDir.y;
    t0 = max(min(a, b), 0.0);
    t1 = min(max(a, b), CLOUD_MAX_DISTANCE);
    return t1 > t0 + 0.01;
}
