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

public float2 cloudWindWorld(float timeSeconds) {
    // Rigid world-space advection: all octaves move together, so clouds travel instead of boiling.
    return float2(-0.18, 0.09) * timeSeconds;
}

public float2 cloudAbsoluteXZ(float3 rebasedPosition, CloudParams params) {
    return rebasedPosition.xz + params.worldOrigin - cloudWindWorld(params.weather.w);
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

    // Every height slice receives a different non-linear horizontal warp. This is a cheap pseudo-3D
    // field: cloud sides stay vertically coherent, but no longer look like 2-D noise extruded upward.
    float2 broadWarp = (float2(cloudNoise2(q * 0.00105 + 17.0),
            cloudNoise2(q * 0.00105 + 91.0)) - 0.5) * 82.0;
    float2 heightWarp = (float2(
            cloudNoise2(q * 0.00155 + h * float2(3.7, -2.1) + 31.0),
            cloudNoise2(q * 0.00155 + h * float2(-2.4, 4.3) + 67.0)) - 0.5)
            * lerp(28.0, 126.0, h);
    float2 shaped = q + broadWarp + heightWarp;

    // Large rounded bodies dominate; two smaller scales add cauliflower lobes without shredding the
    // silhouette into thin white wisps.
    float macro = cloudFbm2(shaped * lerp(0.00175, 0.00132, tower)
            + h * float2(0.31, -0.23));
    float puffs = cloudNoise2(shaped * 0.0046
            + (p.y - base) * float2(0.0028, -0.0021));
    float detail = cloudNoise2(shaped * 0.0092
            + (p.y - base) * float2(-0.0043, 0.0036));
    float erosion = cloudFbm2(shaped * 0.014 + h * float2(1.3, -0.9));

    float coverage = lerp(0.60, 0.43, overcast) - moisture * 0.035;
    // Narrow only the very top, leaving a deep vertical body below it.
    coverage += smoothstep(0.58, 1.0, h) * 0.075;
    float field = macro * 0.64 + puffs * 0.27 + detail * 0.09 + profile * 0.22 - coverage;
    float boundary = smoothstep(-0.085, 0.12, field);
    field -= (1.0 - boundary) * (erosion - 0.5) * 0.045;
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
