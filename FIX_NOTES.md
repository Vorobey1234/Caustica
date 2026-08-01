# Rendering fix notes — revision 2

## Fixed

- Volumetric weather and cloud shadows:
  - added a shared, wind-driven 2.5-D cloud volume with 370–550-block masses and tall billowed tops;
  - biome temperature/moisture and kilometre-scale absolute position smoothly select cumulus, broken
    dry cloud or low storm-deck forms without the old 4096-block repetition/jump;
  - cloud bodies receive warm low-sun rims, darken from below during storms, stop moving while paused,
    and provide optical-centroid + wind motion vectors for temporal reconstruction;
  - the exact same density field attenuates sun NEE and volumetric-fog injection, so moving cloud shadows
    on terrain and fog shafts match the visible sky.
  - clear daytime haze now uses solar elevation, reaching stronger extinction and adding blue skylight
    scattering instead of only darkening distant terrain.

- Camera-centred circles / moire bands:
  - restored ray-footprint mip selection instead of forcing mip 0;
  - changed atlas/material minification from nearest to linear while retaining nearest magnification;
  - kept trilinear mip blending and added a small positive LOD bias for oblique pixel-art surfaces.
- Volumetric fog:
  - increased the march from 8 to 24 density samples;
  - interpolates visibility between sparse shadow samples, removing large block/slab transitions;
  - replaced 2-D cell noise with smooth world-space 3-D modulation;
  - remains strongest in rain/thunder, and now also appears in clear weather at sunrise and sunset.
- Puddles remain enabled with a continuous world-space mask across block borders.
- Clean builds without the DLSS SDK remain available through `-PnoNgx` / `build-no-dlss.ps1`.

## Build on Windows

```powershell
.\build-no-dlss.ps1
```

Or manually:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
$env:Path = "$env:JAVA_HOME\bin;$env:VULKAN_SDK\Bin;$env:Path"
.\gradlew.bat --stop
.\gradlew.bat clean assemble -PnoNgx
```

Output: `build\libs\caustica-0.1.0.jar`.

## Visual checks

1. Test Normal, Albedo and Roughness debug views at shallow viewing angles. Circular bands should be
   replaced by smooth minification rather than mip-0 moire.
2. Clear weather: test around sunrise and sunset while looking toward the low sun through trees or a
   doorway. A lighter warm haze/shaft effect should appear.
3. Rain/thunder: shafts should remain stronger, but the fog should no longer break into large slabs.
4. Puddles should still form on exposed horizontal terrain during rain.

## v4 rendering fixes

- Corrected ray-cone texture LOD for grazing angles. The old scalar footprint ignored the
  1/cos(theta) stretch on floors, roofs, and oblique walls, which caused camera-centred circular/spiral
  moire bands.
- Changed the world/material atlas sampler to bilinear-within-mip + trilinear-between-mips. UI and font
  samplers remain nearest-neighbour.
- Reworked volumetric fog to use scalar shadow visibility, bounded direct scattering, 32 march samples,
  denser visibility control points, and low-amplitude world-space density variation.
- Clear-weather haze now appears around both sunrise and sunset, including a short interval before/after
  the sun crosses the horizon.


## BMFR texture clarity fix

- BMFR now honors the shared realtime-denoiser resolution and can run before NRD and realtime OIDN in the fixed denoiser chain.
- Native-size fallback presentation uses an exact Vulkan image copy rather than a filtered blit.
- BMFR restores bounded current-frame detail only where the albedo guide confirms real texture variation, keeping pixel-art textures and foliage crisp without a generic sharpening pass.
- Temporal persistence was reduced slightly so moving texture detail does not soften into history.
- The ray-traced block atlas now uses nearest texel filtering inside each mip while retaining linear mip-to-mip blending, restoring Minecraft-style pixel texture edges.

## Fog and puddle shape fix

- Replaced the many discrete binary fog-shadow slices with two deterministic, soft celestial visibility
  control points and analytic extinction integration. This removes repeated building silhouettes, depth
  bands and blue-noise crawl while retaining broad volumetric light shafts.
- Fog shadow visibility uses complementary three-ray near/far control points across a slightly widened
  sun/moon disc, producing a stable penumbra without the previous ten visibility traversals per pixel.
  Direct fog scattering remains tightly energy-bounded.
- Puddle noise now uses smaller 4-6 block patches with higher-frequency edge breakup. The full-rain fill
  threshold was raised, reducing the wet mirror area from most of a surface to isolated pools.
- Rain exposure now blends one vertical and three evenly spaced local angled sky rays. Roofs, overhangs
  and placed blocks therefore dry the ground gradually instead of stamping a hard square into a puddle.

## Distant Horizons glass fix

- Distant Horizons' transparent capture buffers are now kept separate from opaque terrain instead of
  being flattened into the solid RT material bucket.
- Coarse glass preserves its baked vertex tint and alpha. Transparent buffers that report an invalid
  fully-opaque alpha use a conservative clear-glass fallback so distant panes do not turn into walls.
- Distant panes now use Caustica's thin-dielectric glass model, including Fresnel reflections,
  refraction/transmission and a low glass roughness.
- Sun and sky shadow rays pass through distant panes with mild colour filtering instead of treating
  them as opaque blockers.
- Distant solid and glass triangles remain on the DH any-hit path so live vanilla terrain continues to
  replace stale LOD geometry near the camera without rebuilding the complete DH acceleration structure.

## Performance optimization pass

### Distant Horizons CPU and memory

- Native DH VBO captures are flattened into one packed byte array per opaque/transparent pass instead of
  retaining one Java object and one byte array for every source buffer.
- Re-submitted VBOs are compared directly against retained bytes. Unchanged passes allocate nothing, and
  an unchanged opaque or transparent pass is reused when only the other pass changes.
- Inactive captures are pruned only after a generous grace budget, keeping long-distance travel memory
  bounded without deleting sections during transient DH LOD swaps.
- The native quad decoder uses direct little-endian reads, a precomputed sRGB-to-linear table and one reused
  12-float quad scratch instead of per-quad arrays and three `Math.pow` calls.
- DH geometry is now written directly into its final category-ordered position/index/UV/material arrays.
  The former FastUtil accumulation plus final concatenation temporarily held two complete copies of a large
  proxy mesh; the optimized path keeps only the final arrays.
- The per-frame terrain-plus-DH instance list is a random-access view with one appended instance, avoiding a
  complete copy of the vanilla terrain instance list every frame.
- CPU conversion and queued GPU builds cooperatively cancel after a world/epoch change. Transient failures
  re-arm the same DH revision after a short delay instead of permanently disabling the proxy.

### Renderer and denoiser hot paths

- Single-BLAS build/free overloads remove temporary singleton-list allocations in DH and terrain upload paths.
- The asynchronous GPU executor reuses its batch and executable-job lists for the lifetime of its worker.
- BMFR descriptor writes are cached across stable image views and refreshed only after a resize/recreation.
- BMFR builds only the upper half of its symmetric 13x13 regression matrix, mirrors it, and distributes the
  work across feature-row invocations. Its 3x3 guide window reuses workgroup-shared samples for interior taps.
- BMFR dispatch dimensions now cover the staggered half-block exactly rather than adding a redundant full
  guard row and column for several common resolutions.
- Fog shadow traversal is reduced from ten to six rays per fogged pixel by using complementary near/far
  penumbra patterns. Rain exposure uses four total sky rays instead of five while remaining isotropic.

### Validation performed in this environment

- All Java sources passed compiler-parser validation; the two heavily rewritten DH classes also passed a
  focused semantic `javac` compile against API-compatible stubs.
- A randomized decoder parity harness, including malformed/out-of-range DH quads, produced byte-for-byte/
  raw-float-identical packed geometry between the previous and direct-final implementations.
- Multi-buffer capture tests covered non-zero buffer positions, null entries, incomplete 64-byte tails and
  changed-byte detection.
- All JSON resources and ZIP integrity are checked before packaging. Full Gradle and SPIR-V compilation still
  require the project dependencies plus `glslangValidator`, `slangc` and `spirv-val` on a networked build host.

## DH VRAM stability, fog falloff and normal-ring fix

### Bounded Distant Horizons residency

- DH geometry is split into BLAS batches capped at 32,768 quads. Only one changed batch owns upload,
  position/index build inputs, build scratch and uncompacted BLAS storage at a time.
- Every changed BLAS is compacted before the next batch starts. Unchanged source meshes reuse their existing
  compacted geometry, avoiding replacement allocations for most steady-state DH refreshes.
- The currently published proxy stays live until an atomic replacement table is complete; old geometry that
  is not retained by the new table is destroyed only after its final graphics-timeline use.
- A second DH capture/rebuild cannot start while a bounded replacement session is active, preventing queued
  revisions from multiplying CPU/GPU work during rapid DH streaming.

### Fog

- Clear air and weather fog now have a camera-centred clear radius followed by an analytically integrated
  density ramp. Nearby blocks remain readable while distant haze becomes noticeably stronger.
- Rain moves the fog inward and raises extinction; clear weather keeps a wider near-camera opening.
- Volumetric-light shadow rays ignore coarse DH LOD triangles. Full-resolution nearby terrain still affects
  fog lighting, but distant low-detail skyscrapers no longer stamp enlarged stair-stepped silhouettes into
  the atmosphere.
- The remaining shaft field uses soft deterministic celestial-disc samples and low-frequency density only,
  avoiding the repeated depth slices and temporal crawl of the previous fog march.

### Material normals

- Canonical normal/AO pages use a dedicated bilinear + trilinear sampler. The pixel-art albedo atlas keeps
  nearest filtering inside each mip, so fixing normal minification does not blur block textures.
- Tangent-space normal amplitude fades continuously with ray-cone footprint after mip selection. This removes
  camera-centred circular LOD bands in normal-only/debug views while leaving geometric normals, albedo,
  roughness and close-up normal detail unchanged.

## DH fog shafts and larger puddles
- Restored Distant Horizons participation in volumetric light shafts using fractional stochastic coverage for coarse LOD occluders instead of fully ignoring DH or producing hard blocky silhouettes.
- Strengthened distance fog and tightened its smooth clear radius around the player.
- Increased puddle basin size and coverage while retaining soft drying around roofs and obstacles.


## DH seam / stability / menu and Minecraft 26.2 compile fix

- moved the RT DH hand-off inward by exactly one chunk;
- restored the raster DH background flags in the display dispatch so low-memory RT proxy refreshes no longer create a several-second disappearance;
- added a live Distant Horizons on/off option to the RT Video Settings section;
- replaced removed Minecraft 26.2 calls (`LocalPlayer.displayClientMessage` and `Minecraft.setScreen`) with mapping-compatible UI handling.

## DH toggle rollback

- Removed the live Distant Horizons chunks toggle from Video Settings and from runtime configuration.
- Distant Horizons integration is always enabled whenever the DH mod is loaded, so a persisted false value can no longer leave the raster/RT proxy and its shadows out of sync.
- Kept the one-chunk-closer hand-off. The final atomic-refresh pass below limits raster DH to initial bootstrap only.

## Atomic DH refresh and lazy packing

- Removed the refresh-time raster-DH hand-off. Raster DH is now used only while the very first RT proxy is
  bootstrapping; after publication, camera visibility, shadows, reflections, fog occlusion and emissive/glass
  materials all remain on the same RT proxy throughout later refreshes.
- DH refreshes keep the currently published proxy live until a complete replacement table is ready, then swap
  old to new atomically. Unchanged source VBO versions reuse their existing compacted BLAS and are not rebuilt.
- Changed DH buffers are no longer expanded into every final float/int mesh at once. The planner retains only
  lightweight raw-buffer slices, and a single low-priority CPU worker packs one <=32,768-quad batch at a time.
  This removes the multi-gigabyte Java-heap burst and GC/system stalls seen on 800-1800 batch horizons.
- CPU DH scanning/packing runs on one minimum-priority daemon instead of virtual-thread carriers competing with
  render, server, C2ME and RT worker threads. GPU upload/build/compaction remains serial and bounded to one batch.

## High-resolution screenshot removal

- Removed the custom high-resolution screenshot button, accumulation setting, capture manager, dedicated render targets, and screenshot-only OIDN path.
- Normal real-time and reference OIDN denoising remain available.

## Pixel-sharp materials, fog and normal-ring pass

- Changed block, entity, LabPBR surface, normal and AO sampling to nearest/nearest with explicit integer mip selection; reduced-resolution denoiser output now uses nearest upscaling too.
- Reduced ray-cone LOD bias so pixel-art textures retain higher-resolution mips longer.
- Normal maps now sample their base texels with point filtering and fade perturbation/AO continuously by projected footprint; no bilinear blur and no discrete normal-map mip rings.
- Strengthened distant fog, moved its onset closer, and replaced the linear density ramp with an analytic smoothstep integral.
- Coarse Distant Horizons geometry is ignored only by fog-lighting rays to prevent giant LOD silhouettes; nearby full-resolution terrain still creates volumetric shafts.

## Point-sampled materials, stronger all-weather fog and progressive DH streaming

### Texture and material sampling

- Block atlas, entity textures, LabPBR surface maps, normal/AO pages, sky pages and material masks now use
  nearest magnification, nearest minification and nearest mip selection. No material sampler in the RT path
  uses bilinear or trilinear filtering.
- Ray-cone LOD is converted to an explicit integer mip and biased toward the higher-resolution level, so
  generated mipmaps remain available for distant stability without cross-mip blur.
- Normal maps read point-sampled base texels and fade tangent-space perturbation continuously by projected
  footprint. This keeps authored normal pixels sharp without bringing back camera-centred normal mip rings.
- Reduced-resolution denoiser output is point-upscaled; native-resolution output remains an exact image copy.

### Fog

- Clear-day extinction and fog contribution were increased, so atmospheric haze remains visible in direct
  daylight instead of appearing only at twilight or during rain.
- Rain still moves the fog onset closer to the player and increases extinction; clear weather keeps the wider
  clean radius. The onset uses an analytically integrated smooth ramp with zero slope at the boundary.
- DH geometry continues to form distant volumetric rays with reduced fractional occlusion, avoiding hard
  low-detail building silhouettes while preserving broad shafts.

### Distant Horizons quality streaming

- Removed the global "wait until all DH uploads are quiet" gate. Refined LOD uploads can be picked up on the
  next periodic refresh even while DH world generation is still active.
- Bounded BLAS batches were increased from 32,768 to 131,072 quads, cutting build/compaction overhead while
  retaining a one-batch transient-memory ceiling.
- Source meshes are ordered near-to-far, with broad coarse coverage first at equal distance.
- The first completed batch is published immediately. Further batches are published as progressive RT
  checkpoints instead of withholding the entire high-quality proxy for minutes.
- Initial raster DH fills only sections not yet streamed into RT. It is disabled permanently after the first
  complete plan; later refreshes remain fully RT and keep the previous geometry until each checkpoint swaps.
- Progressive snapshots preserve old, unresolved batches and drop stale batches only at the final checkpoint,
  preventing holes, shadow loss and material disappearance.
- The DH CPU worker now runs just below normal priority instead of minimum priority, preventing starvation by
  DH/C2ME generation workers while remaining less intrusive than gameplay and render work.

## Distant Horizons quality-aware LOD refinement

- The RT proxy now reads Distant Horizons' active maximum horizontal resolution and horizontal-quality/dropoff settings.
- Quality changes are detected live and cancel only unpublished work; the currently published RT proxy remains visible while a new quality-aware plan is prepared.
- Captured native DH VBOs are tagged with their observed horizontal data-point width, allowing fine meshes to be prioritized instead of always publishing the coarsest parents first.
- Fine LODs are streamed first inside a quality-dependent distance band. Higher DH quality settings extend that fine-first band farther from the camera.
- A coarse parent is removed as soon as completed finer children fully cover its area. This prevents stale low-detail geometry from masking the better LOD for the remainder of a long horizon rebuild.
- During world load and for 15 seconds after a quality change, the RT proxy checks for refined DH uploads every second rather than waiting for the normal refresh interval.
- Coarse geometry remains in place until complete finer coverage is ready, so refinement does not create temporary holes or missing shadows.

## Build packaging fix

- Restored `buildSrc/src/main/groovy/dev/comfyfluffy/caustica/build/GenerateShaderRecords.groovy`.
- Fixes Gradle configuration failure: `unable to resolve class dev.comfyfluffy.caustica.build.GenerateShaderRecords`.

## Multiplayer Distant Horizons capture fix

- The DH VBO upload hook now forwards the actual level wrapper that produced each upload. Remote-server
  worlds no longer depend on DH's singleplayer-only `getSinglePlayerLevel()` accessor for vertical placement.
- If a DH version supplies an internal multiplayer wrapper that cannot be reflected through the public API,
  the current Minecraft `ClientLevel` minimum Y is used instead of dropping the entire uploaded mesh.
- The RT snapshot no longer returns an empty horizon when DH's renderer-internal enabled-section list is
  temporarily unavailable, unvalidated, or keyed differently on multiplayer. It falls back to current-world
  captured VBOs, which are still radius-filtered by the RT planner and cleared on every ClientLevel change.
- Singleplayer active-section pruning remains unchanged when DH exposes a valid enabled-section list.
## Vulkan device-fault hardening

- Disabled `VK_EXT_opacity_micromap` automatically on AMD GPUs. The reported fault was an invalid GPU read adjacent to the terrain opacity-micromap triangle buffer; AMD now uses the normal any-hit alpha-test path instead.
- Added an explicit unsafe diagnostic override (`-Dcaustica.rt.ommAllowAmdUnsafe=true`) rather than allowing the existing config value to re-enable the unstable path accidentally.
- Reserved the graphics timeline retirement value at the beginning of terrain use, closing a window where a BLAS/table replacement could otherwise be destroyed before an in-progress submission had signalled its use.
- Added fail-fast validation for null scratch, geometry, micromap and BLAS device addresses before recording Vulkan acceleration-structure commands.
- Added TLAS instance validation for null BLAS addresses and invalid 24-bit custom indices / 8-bit masks.

The fallback changes performance only for alpha-tested terrain on affected AMD devices; rendering behavior remains the same.


## Interlaced Ray Budget

- Ray Budget no longer reduces `renderW`/`renderH` with a square-root resolution scale.
- Trace color and all guide buffers remain at the selected denoiser/DLSS input resolution.
- 1/2 uses a rotating true checkerboard pattern.
- 1/4, 1/8 and 1/16 use rotating 2x2, 4x2 and 4x4 sparse phases.
- A race-free in-place compute resolve expands each non-overlapping sampling tile without bilinear filtering.
- Temporal, BMFR, NRD, DLSS-RR, accumulation and frame-generation histories reset when the rate changes.
- Reference OIDN still forces full-rate tracing.

## Reservoir local lighting and lower-noise sampling

- Added a compact 24-entry analytic point-light list to the reflected `WorldPush` ABI. It is populated
  from nearby placed light-emitting blocks, luminous items held in either hand, and luminous dropped
  `ItemEntity` stacks.
- Nearby static emitters are scanned incrementally in slices of at most 2,048 block states per frame and
  retained in a bounded strongest-64 set; only the strongest/nearest 24 lights are uploaded each frame.
  Held and dropped lights are evaluated
  every frame and receive priority boosts so they cannot be crowded out by a lava pool or dense lamp wall.
- Direct local illumination uses weighted reservoir importance sampling over the complete uploaded light
  list. The target function is the unoccluded BRDF contribution, and the selected light is evaluated with
  one ray-traced visibility query plus the exact categorical reweighting factor.
- Primary surfaces take two independent local-light reservoir samples. First-indirect vertices use one,
  replacing the old strategy of waiting for a random GI path to hit a tiny torch/item mesh.
- Primary sun/moon soft shadows use an antithetic pair of square-emitter samples, reducing one-sample
  penumbra noise while keeping the same physically shaded BRDF and ray-traced visibility path.
- Full-strength emissive material radiance was increased from 5.0 to 6.25 HDR units. Held lights are
  amplified, and dropped luminous items use the strongest multiplier as requested.
- The built-in temporal estimator now stores a 1-32 frame sample age in history alpha, converges toward
  longer averages in stable regions, and reacts to color/lighting changes and motion before accepting old
  history. This reduces static noise without increasing the spatial blur radius or reintroducing ghosting.

## AMD RT pipeline compiler crash fix

- The supplied `hs_err_pid18892.log` proves the native crash occurs inside
  `vkCreateRayTracingPipelinesKHR` while AMD's driver compiles the world RT pipeline, before SBT creation
  or the first trace dispatch.
- Added a dedicated AMD shader family (`world_amd.*`) with a small PhysicalStorageBuffer view and no
  dynamically indexed 24-light reservoir loop.
- AMD evaluates the two CPU-ranked strongest local lights at constant indices with deterministic shadow
  rays. This keeps held/off-hand/dropped-item lighting strong and removes direct-light sampling noise.
- Non-AMD devices retain the full many-light reservoir shader.
- Set `-Dcaustica.rt.amdUnsafeReservoirShader=true` only to reproduce/test the unsafe full shader on AMD.


## Ray Budget jitter + checkerboard update
- Added a runtime **Ray Budget Jitter** toggle in the RT video options and config (`caustica.rt.rayBudgetJitter`).
- Ray Budget now uses a **stable checkerboard/dithered phase** by default instead of always rotating every frame, which reduces the visible “jumping”.
- **Ray Budget Jitter** applies a bounded sub-pixel offset and a per-tile exact-pixel phase cycle. Tiles
  use different odd phase strides; raygen and resolve use the same schedule without a global slide.
- Standard/AMD raygen and the interlace resolver use the same stable-default or jittered phase mapping.
  The legacy packed global-rotation bit remains zero for stale-SPIR-V safety.


## Native checkerboard history fix
- Fixed the low-pixel/block-upscaled appearance produced by the first checkerboard resolver.
- Added a full-resolution per-pixel validity image. A pixel is marked valid only when its exact checkerboard phase receives a real ray.
- Missing pixels are spatially filled only during warm-up. Once a pixel has been traced, later unsampled frames preserve that native-resolution history instead of overwriting it with a copied tile sample.
- At reduced budgets, unsampled native pixels are reconstructed from stable exact-pixel history; jitter
  never turns reconstructed pixels into false Monte-Carlo samples.
- Changing Ray Budget or its jitter policy clears the validity mask and denoiser histories together.


## Local emissive lighting + entity normal fix
- Added a **Local Emissive Lighting** runtime option with `Surface / ReSTIR` and `Hybrid / Proxy Lights` modes.
- `Surface / ReSTIR` disables analytic proxy/point lights entirely, eliminating block halo artifacts from placed emissive blocks.
- `Hybrid / Proxy Lights` preserves the previous brighter local-light approximation as an optional fallback.
- Dynamic entity quads now store a packed tangent frame shared by both triangles of each quad, eliminating the diagonal normal-map seam where one triangle shaded differently from the other.


## Checkerboard motion-blur fix
- Unsampled checkerboard pixels no longer inherit a traced neighbour's motion/specular-motion vectors.
- For every unsampled pixel, motion is forced to zero while its existing color/depth/normal history is preserved.
- This removes the severe fake motion-blur / smear caused when temporal reprojection, BMFR, or RR treated copied neighbour motion as real per-pixel motion.

## NRD Reference first-dispatch native crash fix
- The `hs_err` stack proves the crash occurred inside `causticaNrdDenoise` during `RtNrdDenoiser.dispatch`, reading address `0x0` on the first NRD dispatch.
- Disassembly mapped the fault to the NRD Integration first-frame path dereferencing `ResourceSnapshot::slots[IN_NORMAL_ROUGHNESS]`.
- The Reference branch previously supplied only `IN_SIGNAL` and `OUT_SIGNAL`, leaving the common guide slot null.
- Reference now supplies `IN_MV`, `IN_NORMAL_ROUGHNESS`, and `IN_VIEWZ` as safe common resources in addition to its signal/output resources.
- The bundled Windows `caustica_nrd.dll` was hot-patched to route Reference through the existing common-guide wrapping path, so Gradle packages the fix without requiring users to rebuild the native bridge.


## Hybrid emissive mode removed
- Removed the `Hybrid / Proxy Lights` option from the RT video menu and configuration.
- Surface-emissive lighting is now the only local-emissive path.
- Analytic point/proxy light collection is permanently disabled, so luminous blocks and items cannot create spherical proxy-light halos.


## Distant Horizons material and emission propagation
- DH RT geometry now preserves the full `EDhApiBlockMaterial` mini-material in `TerrainPrim.flags` instead of collapsing every opaque LOD quad to one generic material.
- DH metal, stone, wood, dirt, deepslate, snow, sand, terracotta, nether stone, grass and leaves now receive distinct roughness/metalness/F0/SSS behaviour in both standard and AMD closest-hit shaders.
- Lava and `ILLUMINATED` DH surfaces retain baked colour and surface emission, with a conservative boost from DH packed light metadata; no analytic point/proxy lights were reintroduced.
- Water and transparent dielectric LODs keep their dedicated material paths, while leaves submitted in the transparent VBO are no longer misclassified as glass.
- `AIR` mini-material quads are discarded before BLAS packing.
- Exact sprite-level LabPBR normal/specular maps cannot be reconstructed from DH's native LOD VBO because it exposes baked colour plus a coarse mini-material rather than the original block-state/sprite identity.


## DH AMD shader compile fix
- Mirrored the `DH_MATERIAL_*` constants into `world_amd_common.slang`.
- AMD closest-hit now compiles with the same Distant Horizons material IDs as the standard shader path while preserving the AMD-safe two-light ABI.


## Distant Horizons world-switch lifecycle fix
- DH CPU VBO captures are now scoped to the identity of Minecraft's current `ClientLevel`; section keys from the previous world cannot leak into the next one.
- The first DH upload or RT snapshot after a level change clears only the old world's captured map and advances the DH revision.
- `RtDistantHorizonsTerrain` now resets GPU proxy/build state on a world switch without deleting VBOs that DH may already have uploaded for the new world.
- `bootstrapComplete` is reset on every world transition so raster DH remains visible until the new RT proxy has actually been published.


## Motion-aware checkerboard animation reconstruction
- Added immutable previous-frame color/normal/depth images for Ray Budget reconstruction.
- Unsampled pixels now use current-to-previous motion-vector reprojection instead of retaining stale geometry indefinitely.
- Moving/deforming entities use relaxed depth/normal disocclusion checks, while static surfaces retain strict native-resolution history.
- Newly visible or rejected pixels immediately fall back to a current traced sample, preventing missing animation parts.
- Checkerboard pixels now carry coherent motion/specular-motion together with their reconstructed guides.
- Built-in temporal history weight is reduced progressively at 1/2, 1/4, 1/8 and 1/16 ray budgets so motion is not averaged away.

- Fixed the built-in temporal reprojection sign: `gMotion` is `previous = current + motion`, matching BMFR, NRD and DLSS. The old subtraction sampled history on the opposite side of moving geometry.

## Mob checkerboard animation fix
- Added a full-resolution previous-motion snapshot for sparse checkerboard reconstruction.
- Unsampled pixels now prefer their own guide-matched historical motion, with a small 3x3 motion dilation for thin mob limbs and silhouettes, instead of blindly using the single traced sample's motion for the whole tile.
- The static-history shortcut now requires both the tile sample and the exact pixel's historical motion to be static and guide-compatible, preventing a static background sample from freezing a moving mob pixel.
- Dynamic entity motion pairing now checks entity object identity plus an ordered geometry-layout hash, preventing recycled IDs or reordered feature submissions from pairing unrelated vertices.
- Rigid mesh reuse is disabled for non-player living entities so skeletal animation always uploads/refits the current posed mesh rather than being approximated as a yaw-only rigid transform.


## Raw accumulation feedback fix
- Raygen accumulation no longer reads `outImage`, because denoisers overwrite that image later in the frame.
- Added immutable raw color/normal/depth history bindings shared with checkerboard reconstruction.
- Raw history is captured immediately after trace/resolve and before BMFR, NRD, SVGF, OIDN or upscale passes.
- Accumulation rejects depth/normal discontinuities, sanitizes NaN/Inf, and uses a bounded 32-frame EMA so DH streaming and animated content can recover.
- Shifted set-0 celestial atlas to binding 12 and blue noise to binding 13 to preserve descriptor ABI.


## Ray-budget accumulation isolation
- Split checkerboard resolved history from native Monte Carlo accumulation history.
- Raygen now writes per-pixel native accumulation color/count, normal, and depth only for the actually traced phase.
- Reconstructed/reprojected checkerboard pixels can no longer be accumulated as fresh ray samples.
- Native sample counts are per pixel (stored in history alpha) and capped at 32.
- Native accumulation is cleared on camera/sequence/ray-budget pattern resets.


## Ray Budget Jitter stationary-history fix
- Jittered 1/2, 1/4, 1/8, and 1/16 schedules cover every native pixel using dephased per-tile odd
  strides, rather than translating one coherent checkerboard across the screen.
- Native raw accumulation is bypassed for sparse sub-pixel jitter: its normal/depth-only validation
  previously averaged different block texels on the same oblique plane up to 32 times. NRD/BMFR/RR
  still perform their guide-complete temporal accumulation normally.
