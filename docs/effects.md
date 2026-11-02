# Particles and effects: design

Effects for games: sparks, smoke, fire, dust from footsteps, muzzle flashes, magic, rain, debris, trails and beams.
They are simulated mostly on the GPU, described as data in the scene library, and drawn by the engine's frame graph
in both eyes of a headset. Decided with the user one question at a time (E1 to E22 below), after a survey of Unreal Niagara, Unity VFX Graph and
Shuriken, Godot 4, PopcornFX, Houdini, bevy_hanabi and the GPU particle talks of Frostbite, Bungie, Naughty Dog,
Guerrilla and AMD (sources at the end), and from a reading of engine `README.md`, `docs/frame-graph-v2.md` (§4.18,
§4.19, §4.20), `docs/pitfalls.md`, `engine-render` (`GeometryPool`, `GpuScene`, `SceneData`, `Deformation`,
`Transparency`, `WeightedBlendedTransparency`, `BlendedTargets`, `GeometryProvider`, `Lights`, `ClusteredLights`) and
scene `README.md`, `docs/animation.md` and `scene-animation`.

The decisions are E1 to E22 (P1 to P22 in the sections below, which keep the options weighed and the reasons); where
a decision differs from the recommendation, the decision rules and the sections below are read with it.

## Decisions made

| | Decision | Notes |
|---|---|---|
| E1 | **CPU and GPU emitters**: each emitter simulates on the GPU (the default, millions of particles) or on the CPU (worker threads; gameplay can read them; small effects skip dispatches); both draw through the same renderers. | Differs from the recommendation (GPU only plus CPU-fed records). |
| E2 | **Emitter logic on the CPU and on the GPU**: the CPU runtime in `scene-effects` is the default (timing, loops, rates, bursts, distance spawning, LOD; testable, servers run it); emitters may also keep their state on the GPU, for huge emitter counts (particles as emitters). | Differs: both, not CPU only. |
| E3 | Effects as **data**: `EffectData` assets of emitters built from a fixed set of modules, values by name; plugins register Slang modules. | As recommended. |
| E4 | **One interpreting kernel**, 64-slot chunks per emitter. | As recommended. |
| E5 | **Survivors copied in order** between two buffers (spawn order kept: deterministic, ribbons). | As recommended. |
| E6 | Capacity **reserved per instance** from its authored maximum. | As recommended. |
| E7 | **Radix sort per view** (per pair of eyes), sorted emitters only, emitters ordered as wholes. | As recommended. |
| E8 | Blended particles in **their own sorted premultiplied pass** after blended materials; order-independent blending per emitter. | As recommended. |
| E9 | Sub-emitters by **GPU events, one generation per frame**. | As recommended. |
| E10 | **Collision chosen per emitter**: analytic colliders on nodes, the terrain height field, last frame's depth, ray queries against the ray tracing structures (where the device traces rays), the physics scene for CPU emitters; an SDF later as another choice. | Differs: choosable, not one fixed set. |
| E11 | **Identical on the same GPU and driver**, close across GPUs. | As recommended. |
| E12 | **Once per frame**, clamped; fixed substeps per emitter on request. | As recommended. |
| E13 | **Ribbons, trails and beams in the first version**, with billboards. | Differs: in v1, not later. |
| E14 | **Lighting chosen per emitter**: unlit, per vertex (light list, sun shadow), per pixel (normal maps), six-way lightmaps; froxel injection later as another choice. | Differs: choosable. |
| E15 | Mesh particles as **GPU-written `GpuScene` instances**. | As recommended. |
| E16 | **CPU bounds** (authored or estimated) for culling and LOD, **GPU bounds** for drawing. | As recommended. |
| E17 | **Float offsets from a high-precision origin per instance**, rebased when far; each emitter simulates in **world space** (particles stay where born) or **local space** (they move with the emitter's node or joint). | World and local space added by the user. |
| E18 | Curves and gradients as **keys evaluated in the shader** (searched per particle), not baked. | Differs: keys, not an atlas. |
| E19 | **One sort and orientation per pair of eyes** in VR. | As recommended. |
| E20 | Off-screen and far behavior **per effect type**. | As recommended. |
| E21 | A new module **`scene-effects`**. | As recommended. |
| E22 | **Gameplay bursts chosen per call**: into one shared instance per asset (thousands of hits, one draw) or as instances of their own (each movable and stoppable). | Differs: choosable. |

---

## 1. Survey

### 1.1 Unreal Niagara

**Model.** A *system* holds *emitters*. Each emitter is a *stack* of *modules* in groups that run top to bottom:
Emitter Spawn and Emitter Update (once per emitter, on the CPU), Particle Spawn (once per particle, at birth), Particle
Update (every frame), Event Handlers, Simulation Stages, and Render (one or more renderers: sprite, mesh, ribbon,
light, component). A system adds System Spawn and Update groups above its emitters. Modules are HLSL or a visual graph.
They read and write *parameters* in *namespaces* (`System.`, `Emitter.`, `Particles.`, `User.`, `Engine.`), and
modules writing the same attribute accumulate in order. `User.` parameters are the per-instance overrides that a game
sets. Emitters inherit from parent emitters: a child overrides or reverts any module.
([overview](https://dev.epicgames.com/documentation/en-us/unreal-engine/overview-of-niagara-effects-for-unreal-engine))

**What it does well:**
- **Composition and reuse.** It has a stack per emitter, emitter inheritance, *dynamic inputs* (any module input can be
  a curve, a random range or a parameter), and scratch-pad modules.
- **Data interfaces** let particles read the world: skeletal and static meshes (surface, vertices, bones), curves,
  textures and render targets, grids (Grid2D/3D, Neighbor Grid 3D), the camera, collision queries and the audio
  spectrum. Rigs, meshes and grids are first-class inputs, not special cases.
  ([UNiagaraDataInterface](https://docs.unrealengine.com/4.27/API/Plugins/Niagara/UNiagaraDataInterface/index.html))
- **Simulation stages** run extra GPU passes per frame. A stage iterates over the particles or over a data interface
  (a grid's cells), with an iteration count. That is how Niagara does fluids, flocking with a neighbour grid, and
  constraint solving.
  ([UNiagaraSimulationStageGeneric](https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Plugins/Niagara/UNiagaraSimulationStageGeneric))
- **Scalability** comes through *Effect Types*, assets shared by many systems. They set the significance mode (distance
  or age), the maximum distance, instance caps per type and per system, update frequency, what happens when culled
  (kill or sleep), proxies, budget scaling, and per-platform overrides. Without a significance handler, caps apply only
  at spawn. ([scalability settings](https://dev.epicgames.com/documentation/en-us/unreal-engine/python-api/class/NiagaraSystemScalabilitySettings),
  [effect types](https://forums.unrealengine.com/t/niagara-scalability-effect-types/264959))
- **Data Channels** (5.3+) let gameplay write events (impacts, footsteps) into a channel. *One* listener system per
  spatial "island" spawns all of them, so a hundred bullet impacts do not make a hundred systems.
  ([data channels](https://dev.epicgames.com/documentation/en-us/unreal-engine/niagara-data-channels-overview))
- **Lightweight (stateless) emitters** (5.4, experimental) never store particles. Each frame they compute every
  particle from its age, analytically, with a restricted module set (gravity, drag, curl noise, sub-UV). The motive was
  memory, CPU cost and iteration time.
  ([lightweight emitters](https://dev.epicgames.com/documentation/en-us/unreal-engine/niagara-lightweight-emitters-overview))
- **Collision** has three methods: the depth buffer (cheap, wrong off screen), the global distance field, and hardware
  ray tracing. Ray tracing is asynchronous and one frame late, and falls back to distance fields.
  ([GPU ray tracing collisions](https://dev.epicgames.com/documentation/en-us/unreal-engine/gpu-raytracing-collisions-in-niagara-for-unreal-engine))
- **Determinism** is a per-emitter switch with a seed. It holds only "as long as delta time is not variable".
  ([emitter data](https://dev.epicgames.com/documentation/en-us/unreal-engine/python-api/class/VersionedNiagaraEmitterData?application_version=5.1))

**What it does badly:**
- **Events are CPU only.** GPU emitters long could not send or receive events. The workarounds are CPU helper
  emitters, the Particle Attribute Reader (whose GPU particle counts lag several frames), or render targets.
  ([UE-64938](https://issues.unrealengine.com/issue/UE-64938),
  [forum](https://forums.unrealengine.com/t/niagara-cpu-emitter-read-particle-location-from-gpu-emitter/1268115),
  [ribbon gaps](https://realtimevfx.com/t/fixing-first-frame-gaps-in-niagara-ribbons/29170))
- **Two simulation targets.** CPU and GPU emitters behave differently, and each module must work on both.
- **Compilation.** Every emitter compiles to its own shaders: slow iteration, and permutations multiply. Lightweight
  emitters exist partly to avoid that.
- **The "GPU fixed bounds" chore.** GPU emitters need authored bounds, or a slow readback.

### 1.2 Unity VFX Graph and Shuriken

**VFX Graph (GPU only).** The graph has vertical *contexts*: Spawn (CPU: rates, bursts, timing), Initialize (at
birth), Update (each frame) and Output (one or more renderers), plus *GPU Event* contexts. Each context is a list of
*blocks*, and operators compute block inputs. Particle *strips* are a data type chosen on Initialize, with a particle
count per strip.
([contexts](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.5/manual/Context.html),
[Initialize](https://docs.unity.cn/Packages/com.unity.visualeffectgraph@10.9/manual/Context-Initialize.html))
- **GPU events.** A Trigger Event block (on die, always, rate, on collide) feeds a GPU Event context into another
  system's Initialize. The child reads its parent's values through *source attributes* ("Inherit Attribute", "Get
  Source Attribute"). Spawn and GPU-event inputs cannot be mixed on one Initialize.
  ([events](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph%4012.1/manual/Events.html),
  [GPU Event](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/Context-GPUEvent.html))
  This is the cleanest GPU sub-emitter design surveyed: the payload is "the parent's attributes", with no
  hand-written struct.
- **Sorting** is set per Output: Auto (on for alpha and premultiplied), On or Off. Modes are depth or a custom key, at
  the cost of an extra compute pass, and sorting forces indirect draw. Sorting is per output and per system, so two
  graphs do not interleave per particle.
  ([output settings](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/Context-OutputSharedSettings.html),
  [forum](https://discussions.unity.com/t/vfx-graph-sorting-order/750060))
- **Six-way lighting** bakes a smoke flipbook lit from six directions into two RGBA maps (right, top, back plus alpha;
  left, bottom, front plus emission). At runtime they are blended by the scene's lights and probes. This is cheap,
  convincing lit smoke, and it has become the industry's answer for lit volumetric sprites.
  ([docs](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/six-way-lighting.html),
  [blog](https://unity.com/blog/engine-platform/realistic-smoke-with-6-way-lighting-in-vfx-graph))
- **Weaknesses:** one compiled shader set per graph (as in Niagara), instancing of many small graphs only from 2022.2
  on, and the documented limits of GPU events (experimental for years).

**Shuriken (CPU, `ParticleSystem`).** It is module based (emission, shape, velocity over lifetime, noise, collision,
sub emitters, trails, texture sheet). Sub emitters fire on Birth, Collision, Death, Trigger (a volume) and Manual
(`TriggerSubEmitter` from script), and inherit chosen properties (color, size, rotation, lifetime, and velocity through
the Inherit Velocity module). Only Birth sub emitters allow several bursts.
([sub emitters](https://docs.unity3d.com/Manual/PartSysSubEmitModule.html)) The module list with checkboxes is the
most learnable authoring model in the survey. Its trails (a history per particle, or ribbons through all particles)
are the model Godot copied.

### 1.3 Godot 4 (GPUParticles3D)

A node with an `amount` (its fixed capacity), a lifetime, one-shot, `preprocess` (prewarm), explosiveness, randomness,
`fixed_fps` with `interpolate`, local or world coordinates, a visibility AABB (it also bounds collision and
attractors), draw order (index, lifetime, reverse lifetime, view depth) and trails. Behaviour is a *process material*:
the fixed-function `ParticleProcessMaterial`, or a custom particle shader.
([properties](https://docs.godotengine.org/en/stable/tutorials/3d/particles/properties.html))
- **Sub-emitters:** constant, at end, at collision, plus `emit_particle()` from a particle shader. Nesting goes "as
  deep as you like", but not recursively, and the child's `amount` caps it.
  ([sub-emitters](https://docs.godotengine.org/en/stable/tutorials/3d/particles/subemitters.html))
- **Collision** is with nodes: boxes, spheres, a *height field* baked around the camera (outdoors) and a baked *SDF*
  (indoors). Modes are rigid or hide on contact, and colliders are filtered by cull mask.
  ([collision](https://docs.godotengine.org/en/stable/tutorials/3d/particles/collision.html))
- **Attractors:** box, sphere and vector field, filtered by cull mask; turbulence by noise.
  ([attractors](https://docs.godotengine.org/en/stable/tutorials/3d/particles/attractors.html))
- **Lessons:** capacity as an explicit `amount`, and particles per second = amount / lifetime, is simple and
  predictable. Fixed fps with interpolation is a good default for cost and stability. Colliders and attractors are
  *scene nodes* that effects find by layer, not parameters of each effect. The weak spot is that the fixed process
  material is a large uber-shader that is hard to extend without writing a whole particle shader.

### 1.4 PopcornFX

PopcornFX is a middleware with its own editor and runtimes (Unreal, Unity, custom engines). Effects are graphs of
*layers*. Spawner scripts and *evolvers* run in order each frame, and renderers include billboards, ribbons, meshes,
lights, decals and sound. The same effect runs on the CPU or GPU in the editor as in the runtime. In v2 a parent layer
passes a *payload* to child layers. Its recent work adds *simulation fences* (to synchronize several compute stages
in a frame), *micro-updates* (substeps within a frame) and *spatial layers* (particles query neighbours stored in a
spatial structure).
([hands-on](https://gamefromscratch.com/popcornfx-particle-creation-software-and-run-time-hands-on/),
[Persistant Studios on 80.lv](https://80.lv/articles/innovating-vfx-in-gaming-the-game-changing-journey-of-persistant-studios/),
[v2 forum](https://realtimevfx.com/t/popcornfx-v2-tests-templates-discussions/6108),
[spatial layers](https://popcornfx.artstation.com/projects/nQLWDK))
The lessons are that one effect description serves CPU and GPU through one compiler, payload-based layer chaining,
and that substepping belongs in the simulation itself. Its public documentation was not reachable for this survey,
so details are from secondary sources.

### 1.5 Houdini ideas

- **Attributes on points.** Houdini's POP and SOP networks treat particles as points with arbitrary named attributes,
  and every operation reads and writes them. Niagara's parameter maps and VFX Graph's attributes are the same idea.
- **Volumes and fields as inputs:** VDB SDFs, velocity fields and density.
- **Bake, then play back.** Expensive simulations (pyro, destruction, fluids) are baked offline into flipbooks with
  motion vectors, vertex animation textures (VAT, SideFX Labs), point caches or vector fields, and the game only plays
  them back. For true-sim this means the runtime should *consume* baked data (flipbooks, 3D vector-field textures,
  VAT meshes, point caches) rather than simulate everything.

### 1.6 GPU particle talks (studios and vendors)

- **AMD / Gareth Thomas, "Holy smoke! Faster particle rendering using DirectCompute"** (AMD Developer Day 2014; the
  GPUParticles11 sample). A persistent *dead list* of free indices, an *alive list* rebuilt every frame by the
  simulate pass, an emit pass that pops dead indices, a *sorted* alive list, and indirect draws because only the GPU
  knows the count. It also *tiles* the screen and rasterizes particles in compute, per tile, to cut overdraw cost.
  ([slides](https://www.slideshare.net/slideshow/holy-smoke-faster-particle-rendering-using-direct-compute-by-gareth-thomas/35656271))
- **Wicked Engine** (Turánszki 2017) has two alive lists (emit writes one, simulate kills and writes the other), view
  distance written by the simulation for the sort, depth-buffer collision, and SPH with a grid.
  ([article](https://wickedengine.net/2017/11/gpu-based-particle-simulation/))
- **Frostbite, "GPU Emitter Graph System"** (Hillaire and Egleus, GDC 2018; Star Wars Battlefront II). A graph-based
  GPU emitter tool that generates shaders. It covers memory management, sorting and rendering.
  ([EA](https://www.ea.com/frostbite/news/frostbite-gpu-emitter-graph-system),
  [GDC Vault](https://gdcvault.com/play/1025132/Frostbite-GPU-Emitter-Graph))
  Also **"Physically based and unified volumetric rendering"** (Hillaire, SIGGRAPH 2015): particles are *voxelized
  into the froxel extinction volume*, so they receive and cast volumetric light and shadow like fog does.
  ([EA](https://www.ea.com/news/physically-based-unified-volumetric-rendering-in-frostbite),
  [course](https://advances.realtimerendering.com/s2015/index.html))
- **Bungie, "The Destiny particle architecture"** (Whitley, SIGGRAPH 2017 Advances). Particle parameters are
  *expressions* compiled to bytecode, and the same bytecode runs on the CPU and, after "relatively simple changes", the
  GPU. Motion is through shape primitives (spheres, planes, points).
  ([80.lv summary](https://80.lv/articles/siggraph-the-destiny-particle-architecture/),
  [course](https://advances.realtimerendering.com/s2017/index.html))
  This is the precedent for an *interpreted* module list instead of one shader per effect.
- **Naughty Dog, "GPU driven effects of The Last of Us Part II"** (Kovalovs, SIGGRAPH 2020). Particles were moved from
  the CPU to the GPU for counts, and GPU access to screen-space buffers (depth, normals) let effects interact with the
  world: settling on surfaces, following geometry. Also Ikeda, "Creative and experimental VFX in TLOU2" (GDC 2021).
  ([SIGGRAPH](https://history.siggraph.org/?p=76957), [GDC](https://gdcvault.com/play/1027072/Creative-and-Experimental-VFX-in))
- **Guerrilla, Killzone Shadow Fall** (Valient, GDC 2014). "Particles should be lit like the rest of the geometry":
  deferred lighting and shadowing of all particles, through a particle G-buffer of lighting samples. Particles sample
  artist-placed *force fields*, so effects need fewer particles.
  ([GDC 2014](https://www.guerrilla-games.com/media/News/Files/GDC2014_Valient_Killzone_Graphics.pdf),
  [postmortem](https://www.guerrilla-games.com/media/News/Files/Valient_Killzone_Shadow_Fall_Demo_Postmortem.pdf))
  Horizon's *GPU procedural placement* (van Muijden, GDC 2017) shares the machinery: append buffers, indirect
  dispatch, density maps. ([GDC Vault](https://www.gdcvault.com/play/1024120/GPU-Based-Run-Time-Procedural))
- **"Beyond emitters: shader and surface driven GPU particle FX"** (Coffin, GDC 2018). Surfaces and their *materials*
  emit particles (a material decides what an impact or a burning surface spawns) through compute and textures.
  ([GDC Vault](https://www.gdcvault.com/play/1025068/Beyond-Emitters-Shader-and-Surface))
- **Ubisoft.** No public Ubisoft talk dedicated to GPU particles turned up. Massive's Snowdrop has a node-graph CPU/GPU
  particle system, per a job listing and coverage of The Division 2.
  ([Guru3D](https://www.guru3d.com/story/ubisoft-gives-peek-into-snowdrop-engine-development-video)) Ubisoft's
  relevant public work is the GPU-driven pipeline (Haar and Aaltonen, SIGGRAPH 2015), whose patterns (indirect draws,
  compaction, cluster culling) the engine already uses.
- **bevy_hanabi** (open source, wgpu). It has init, indirect and update compute passes generated per effect, ping-pong
  buffers, an indirect pass sizing the update from the alive count, batching of effects to cut draws, and GPU spawn
  events for child effects (an event buffer per batch, a child-to-parent map).
  ([repo](https://github.com/djeedai/bevy_hanabi), [notes](https://medium.com/@Sou1gh0st/gpu-particle-research-bevy-hanabi-part-2-1354fc021f38))
- **Sorting.** AMD FidelityFX Parallel Sort is a 4-bit-per-pass radix sort (count, reduce, scatter) with *indirect*
  execution, for Vulkan. ([repo](https://github.com/GPUOpen-Effects/FidelityFX-ParallelSort))

### 1.7 Rendering references

- Soft particles fade by the depth difference to the opaque scene (Lorach, NVIDIA 2007).
  ([paper](https://developer.download.nvidia.com/whitepapers/2007/SDK10/SoftParticles_hi.pdf))
- Off-screen, low-resolution particles with a mixed-resolution upsample (Cantlay, GPU Gems 3 ch. 23).
  ([chapter](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-23-high-speed-screen-particles))
- Weighted blended OIT (McGuire and Bavoil, JCGT 2013), the engine's `WeightedBlendedTransparency`.
  ([paper](https://jcgt.org/published/0002/02/09/))
- Flipbook frame blending with motion vectors (Lozar; also Niagara's SubUV motion-vector blending): smooth playback
  of 4×4 or 8×8 baked sims. ([article](http://www.klemenlozar.com/frame-blending-with-motion-vectors/))
- Curl noise for divergence-free turbulence (Bridson et al., SIGGRAPH 2007).
  ([paper](https://www.cs.ubc.ca/~rbridson/docs/bridson-siggraph2007-curlnoise.pdf))

### 1.8 What the survey says, in short

1. **Emitter logic on the CPU, particle logic on the GPU** is what Niagara (emitter groups), VFX Graph (the Spawn
   context) and Godot (amount, rates, explosiveness) all do. Spawn counts are cheap, deterministic and need
   gameplay inputs; per-particle work is what scales.
2. **One simulator per behaviour.** Niagara's and Shuriken's CPU/GPU split costs twice. VFX Graph is GPU only;
   Destiny and PopcornFX run one description through one compiler. The engine's §4.19 already says: *no* switching one
   system between CPU and GPU.
3. **GPU sub-emitters through events whose payload is the parent's attributes** (VFX Graph, bevy_hanabi), consumed by
   a spawn pass. Niagara's CPU-only events are the cautionary tale.
4. **Batch gameplay-driven bursts into one shared instance** (Niagara Data Channels), so a hundred impacts are not a
   hundred systems.
5. **Scalability is assets shared by many effects** (Niagara Effect Types): distance, caps, significance, and spawn
   scaling by quality.
6. **Compiling one shader set per effect hurts iteration** (Niagara, VFX Graph). Destiny's bytecode and Niagara's
   lightweight emitters both move away from it.
7. **Lit smoke is six-way lightmaps today. Unified volumetrics** (Frostbite) is the high end.
8. **Colliders and attractors are scene objects found by layer** (Godot), plus depth, SDF or ray tracing for "the
   world".

---

## 2. Recommended architecture for true-sim

### 2.1 Principles (proposed, P1 to P22 in section 4)

| | Recommendation | Why |
|---|---|---|
| P1 | **Particles simulate on the GPU only.** Gameplay-relevant particles are CPU-fed records drawn by the same renderers (§4.19). | One simulator per behaviour; §4.19 already chose this. |
| P2 | **Emitter logic on the CPU, in the scene library:** timing, loops, rates, bursts, distance emission, spawn counts, LOD. | Deterministic, testable without a GPU, servers can replicate it; this is what every surveyed engine does. |
| P3 | **Effects are data (`EffectData` assets),** built from a fixed set of modules with parameters. Plugins add module types in Slang, as they add material types. | Matches materials (Slang as the source of truth, generated records); works for editors and files. |
| P4 | **One uber-kernel interprets each emitter's module list.** The pool is cut in chunks of 64 slots, one emitter each, so a wave's branches are uniform. | No shader per effect: effects load at runtime with nothing to compile (the Destiny bytecode precedent). |
| P5 | **Order-preserving compaction:** ping-pong buffers, count → scan → update-and-scatter. | Deterministic order; dense indirect draws; ribbons for free; costs no more bandwidth than updating in place. |
| P9 | **Sub-emitters through a GPU event buffer** (the parent's attributes as the payload), spawned in the same frame, one generation per frame. | GPU-native, same-frame response, bounded memory. |
| P8 | **Blended particles draw sorted and premultiplied** at `Anchor.TRANSPARENT`, after the blended materials; an emitter may opt into WBOIT instead. | Premultiplied alpha does alpha and additive in one blend; WBOIT is wrong for additive. |
| P15 | **Mesh particles are GPU-written instances** in a reserved range of the `GpuScene`. | Culling, LOD, shadows, the visibility buffer, materials and ray tracing all come free. |
| P19 | **VR: simulate once a frame; sort and orient per stereo pair** (the head's center). | Both eyes see the same particle order and orientation: no stereo rivalry; half the sorting. |

### 2.2 Where things live

**scene, new module `scene-effects`.** Its dependencies are core, assets, common-math and common-jobs. It has nothing
of rendering, so a server replicates which effects play.
- `EffectData`, an asset (`EffectAssets.EFFECT`): emitters, user parameters with defaults, and an `EffectType` (its
  budget class). Equal data is one asset (`data.ref()`), as for `MaterialData`.
- `EmitterData` has:
  - timing: delay, duration, loops, loop delay, prewarm;
  - its *spawn* (rates, bursts, per distance, from events) and capacity;
  - *initializers* (at birth) and *updaters* (each frame), as module records with parameters;
  - *events* it sends (birth, death, collision, periodic) and which emitter they spawn;
  - *renderers* (billboard, mesh, ribbon, trail, beam, later light and decal);
  - space (world or local) and its sort mode.
- Module records are generated from Slang structs (`[ParticleModule]`), as material parameters are. Scene keeps them
  *by name and field*, like `MaterialData`, so scene still knows no engine types. The engine resolves names against its
  registry. An unknown module is an error with "did you mean…".
- Values: `FloatCurve`, `ColorGradient`, `Range` (uniform random between two values), and `Binding`. A binding is a
  module input taken from a constant, a user parameter, a curve over normalized age, a curve of a user parameter, a
  curve of speed, or a random range.
- `Effects`, the per-scene store of playing effects (a component on nodes, §4.20 style), with a change log on the
  scene's clock. For each playing effect it holds:
  - the effect asset, node and seed;
  - start time (world clock), state (playing, stopping, stopped);
  - user parameter values and the attachment (node, local offset, inherit velocity).
- `EffectRuntime`: the CPU half of the simulation. Per instance and emitter it advances timing and computes this
  frame's spawn counts, with sub-frame birth times and positions along the emitter's motion. It decides LOD and
  culling by significance. It is deterministic and allocation-free per frame, like `Animators`.
- `EffectType`: maximum distance, instance caps, significance (distance or age), spawn scale per quality level, what
  happens when culled (kill, pause, keep simulating), and a priority.

**engine-render (`com.truesim.engine.render.particles`):**
- `ParticlePool`: the shared particle buffers (two ping-pong copies, device-local), sized by a budget. It allocates
  ranges in chunks of 64 slots per emitter instance (`RangeAllocator`, deterministic). It grows like `GeometryPool`:
  doubled, copied on the GPU next frame, the old buffer retired through frame slots.
- `[HostLayout] struct Particle` in `particles/common.slang`. The Java layout is generated (`slang.javaPackage`), so
  Java and Slang share one source (pitfalls: "a layout computed twice").
- `EmitterTable`: a record per emitter instance (`RecordBuffer`, a few change per frame): its range, module list
  offset, origin (two floats per axis), transform, previous transform, user parameter block, renderer settings, LOD
  fraction and bounds slot.
- `ModuleProgram`: the compiled module lists. Each emitter's modules are encoded once per `EffectData` into a word
  stream (opcode, parameter offsets), shared by every instance of the effect, together with the effect's curves.
- `CurveAtlas`: every curve and gradient baked into rows of an `RGBA16F` texture (256 samples a row, linear filtered),
  allocated and freed with effects, like material table blocks.
- `SpawnRing`: the per-frame spawn records the CPU writes (emitter instance, count, first spawn index, birth-time
  spread, start and end transforms, an inherited velocity). This is §4.19's spawn-record ring, in a `FrameBuffer`.
- `CpuParticles`: §4.19's CPU-fed emitters. A gameplay system writes finished particle records each frame into a
  per-frame mapped buffer, through the generated writer, and they are drawn by the same renderers.
- Techniques:
  - `ParticleSimulation`, a frame technique (once per frame, every view sharing it), on the async compute queue;
  - `ParticleDraw`, per view: culling, sorting, billboards, ribbons and trails;
  - mesh particles need none: they are `GpuScene` instances.
- Keys: `Keys.PARTICLES` (the simulated pool, a buffer) and `Keys.PARTICLE_EVENTS`, which plugins `modify` for custom
  forces and collision (§4.18).

**engine-scene:** `EffectSync`, like `SceneSync`. It follows `Effects`' change log. It acquires `EffectData` (and its
textures and meshes) through `SceneResources` and encodes the module programs. Each frame it runs the
`EffectRuntime`'s spawn step and writes emitter records (transforms of attached nodes from the scene graph in doubles;
joints are nodes) and spawn records.

**engine-app:** an `Effects` plugin, giving a world's effects with the frame graph wired: the simulation in the frame,
a `ParticleDraw` per view (`addPerView`), and the quality setting `effects.quality`.

**scene-animation:** nothing changes. Animation events reach effects through `animators.listen` (section 2.6).
`scene-effects` does not depend on `scene-animation`. A small bridge can come later.

### 2.3 Data on the GPU

```slang
[HostLayout]
public struct Particle            // 64 bytes; float offsets from the emitter instance's origin
{
    public float3 position;  public float age;        // seconds since birth
    public float3 velocity;  public float lifetime;   // age >= lifetime: dead (or the DEAD flag)
    public float4 color;                              // linear, premultiplied in the renderer
    public float2 size;      public float rotation;   public uint seed;   // hash of (effect seed, emitter, spawn index)
    public uint flags;       public uint spawnIndex;  public float2 custom;  // flipbook frame, ribbon u, user data
};
```

- **Positions** are float offsets from an origin per emitter instance (two floats per axis, like instances). The draw
  subtracts the camera as instances do, so effects are precise 7,000 km out.
  - **World-space emitters** keep the origin fixed at spawn. The CPU *rebases* it when the instance has moved more than
    a few kilometres (a car's exhaust on a long drive): a tiny pass subtracts the shift from that range.
  - **Local-space emitters** store positions in the instance's frame.
- **Extra attributes** for emitters that need them (normal, angular velocity, mesh particle orientation, trail history)
  live in side arrays laid out per emitter range, so the common particle stays 64 bytes.
- **Random numbers:** `rand(seed, salt)` is a PCG hash. The seed is fixed per particle at birth, and each module has
  its own salt. Values do not depend on the slot, the thread count, the order of atomics, or the order of modules.

### 2.4 A frame

`FIXED` → `UPDATE` (the game plays, stops, moves and sets parameters) → `ANIMATE` → `LATE` → scene update and
**effect sync** → render.

**Effect sync, on the CPU, allocation-free:**
1. **Follow the change log.** Effects added or removed claim or free ranges, emitter records and module programs.
   A failed reservation applies the effect type's policy: lower LOD, steal the least significant instance, or skip.
2. **Advance each playing instance by the world clock's delta.** A paused world pauses its effects (D128).
3. **Timing:** delay, duration and loops. Then **significance:** distance to the nearest camera and age, against the
   effect type's caps and budgets. This decides each instance's LOD (a spawn scale, an update rate) or culls it.
4. **Spawn counts per emitter instance:**
   - rate × dt plus a carried fraction;
   - bursts at their times, across loop ends;
   - per unit of distance travelled;
   - and the *birth times* within the frame.
   Each count becomes one spawn record carrying the previous and current transforms, so the spawn pass places
   particles along the motion (no gaps behind fast emitters).
5. **Write the frame's records.** Emitter records come from the attached nodes' world transforms, the user parameters
   and the bounds. Spawn records and bursts from `effects.burst` go into the ring.

**On the GPU, in the frame scope, once a frame** (async compute where the GPU has it; before culling, since mesh
particles are instances):

| Pass | What | Dispatch |
|---|---|---|
| `particle upload` | Emitter record scatter (`RecordBuffer`) and the spawn ring | frame start |
| `particle count` | Per chunk: survivors (`age + dt < lifetime`, not flagged dead), by wave ops | indirect: alive chunks |
| `particle scan` | Per emitter: exclusive scan of chunk survivors, then each emitter's spawn room (capacity − survivors) | one group per 1,024 chunks |
| `particle update` | Reads copy A. Runs the emitter's updaters (forces, noise, collision, attribute curves) and integrates. Writes survivors to copy B at their scanned slot (order kept). Appends events. Accumulates bounds (atomic min/max on ordered ints). Writes trail history. | indirect |
| `particle spawn` | One thread per new particle. Sources are the spawn records, then this frame's events, both clamped to room (overflow counted). Runs the initializers, places the particle at its birth time along the emitter's motion, and integrates its remaining part of the frame. | indirect, from the counts |
| `particle args` | Per emitter instance: alive count, draw and dispatch arguments, LOD render fraction. Mesh particles write their `Instance` records into the reserved `GpuScene` range. | one thread per emitter instance |

- **Deaths** decided in `update` (collision kill, kill volumes) set the `DEAD` flag and leave at the next `count`. A
  particle killed by collision lives one more frame, invisibly (its alpha is set to 0).
- **Events of this frame's children** are consumed at the next frame's spawn. That is one generation per frame, so a
  burst on death of a burst on death takes two frames.
- **Statistics** (alive counts, overflows, events dropped) come back through `g.readback`, a few frames late. They are
  for budgets and debugging only, never for gameplay (§4.19).

**Per view** (each `ParticleDraw`; a VR pair shares the work in brackets):

| Pass | What |
|---|---|
| `particle cull` | Emitter instances against the view's frustum, by their GPU bounds; distance LOD (draw a fraction, a stable subset by `spawnIndex` hash). Lists the visible draws per renderer. |
| `particle sort` | [Per pair] Keys are depth along the pair's center ray, for sorted emitters only. A radix sort, indirect (FidelityFX-style 4 bits a pass); 16-bit keys where an emitter is small. |
| `particle draw` | At `Anchor.TRANSPARENT`, after `Transparency.ID`, over `SCENE_COLOR`, testing `SCENE_DEPTH`. Billboards, trails and ribbons in sorted order, premultiplied (`ONE, ONE_MINUS_SRC_ALPHA`). Soft fade reads depth. Lit emitters read `LIGHT_LIST` and the shadow inputs. Optional: into a half-resolution target, then a depth-aware upsample over the scene. |

Mesh particles need no pass of their own. As `GpuScene` instances they are culled, drawn, shadowed and shaded by the
existing techniques, and their occlusion history works because their ids are stable.

### 2.5 Rendering paths

- **Billboards:**
  - facing the camera position (the pair's center in VR) or facing the view plane;
  - velocity-aligned with stretch by speed (sparks, rain);
  - axis-locked (cylindrical: fire, grass-like);
  - free (oriented by a particle normal: shockwave rings on the ground).
  Four vertices from `SV_VertexID`, no vertex input (as the engine's draws). Sub-rectangle trimming (fitted quads or
  polygons per flipbook frame) cuts empty overdraw.
- **Flipbooks:** a frame from age, speed or a parameter. Two frames blended, optionally with motion vectors from a
  second texture. Textures are bindless slots of `Textures`, as in materials.
- **Soft particles:** fade by the depth difference to the opaque scene. Near-camera fade.
- **Blending:** premultiplied alpha only. Additive is premultiplied with alpha 0, and "alpha to add" blends between
  them (glowing embers that thin out), with one pipeline. Opaque and masked billboards (leaves, debris) draw
  separately through `SceneDraw` later, as a geometry provider.
- **Lit:**
  - *unlit* (emissive color only, the default);
  - *lit per vertex*: the light list's lights evaluated at the particle's center, plus sun shadow, sun and sky;
  - *lit per pixel* with a normal map (a sphere normal for round puffs);
  - *six-way* lightmaps for smoke.
  All read `lights.slang`'s `ILightList`, so every light binning works. The shading model is the engine's (§4.18:
  render methods stay fixed). A sun-only fallback applies without a light list.
- **Trails** keep a history per particle: N past positions in a ring per particle (N = 4 to 32), drawn as a
  camera-facing strip. They fit sparks and tracer rounds and do not depend on order.
- **Ribbons** connect *the particles of one emitter instance in spawn order*. Compaction keeps that order, so particle
  i and i+1 are neighbours in the buffer, and the vertex shader builds the strip from them. They suit sword swooshes
  and long smoke trails. Several ribbons per emitter use a ribbon id and, inside the range, a per-ribbon run (the scan
  already gives per-emitter offsets; a second key level is sorted only when needed).
- **Beams:** analytic segments between two points (an emitter's two user parameters or two nodes), subdivided with
  noise. No simulation.
- **Mesh particles:** `GpuScene` instances that the GPU writes: mesh, material, orientation (from velocity, random, or
  angular velocity) and scale. Tint and alpha per particle need the per-instance custom data that README promises
  ("custom data per instance will be buffers by id"). Until then they use the material's color.
- **Distortion** (heat haze, shockwaves) draws offsets into a half-resolution buffer and a pass offsets
  `SCENE_COLOR` reads. It reuses `SceneBehind`'s copy. It comes later.
- **Lights from particles** come later: a GPU-written light range in `Lights` (count from the GPU) that
  `ClusteredLights` bins. It needs light binning to read a GPU count. **Decals** come with a decal system.
- **Volumetric look:** six-way smoke now. Voxelizing particles into froxels (Frostbite) when the engine gets volumetric
  fog.

**Overdraw** is the cost that matters most on a headset at 90 Hz:
- each emitter has a maximum on-screen size fraction (big near-camera sprites fade or shrink);
- a half-resolution target per emitter (a render flag) with the mixed-resolution upsample;
- distance LOD draws a fraction of the particles, with sizes grown to keep coverage;
- `DynamicResolution` already scales the whole view.

### 2.6 Authoring and the game API

**Effects are data, built in code or loaded later from files.** A serialization format comes in its own repository,
like the scene's.

```java
// Typed constants, declared once.
static final EffectParameter.Float INTENSITY = EffectParameter.ofFloat("intensity");
static final EffectParameter.Color TINT = EffectParameter.ofColor("tint");
static final EffectType IMPACTS = EffectType.builder("impacts")
    .maxDistance(80).maxInstances(64).significance(Significance.AGE).whenCulled(Culled.KILL).build();

// An effect: emitters, each a spawn, initializers, updaters, events and renderers.
EffectData sparks = EffectData.builder(IMPACTS)
    .parameter(INTENSITY, 1f).parameter(TINT, Color.rgb(1f, 0.6f, 0.2f))
    .emitter("sparks", e -> e
        .capacity(400).space(Space.WORLD).duration(0.2).loops(1)
        .spawn(Spawn.burst(0, Bind.param(INTENSITY).times(150)))
        .init(Shape.cone(Degrees.of(30)).radius(0.02),
              Init.lifetime(Range.of(0.3, 0.8)), Init.speed(Range.of(4, 11)),
              Init.size(Range.of(0.01, 0.02)), Init.color(Bind.param(TINT)))
        .update(Forces.gravity(), Forces.drag(0.6f),
                Collide.depth().bounce(0.35f).friction(0.2f).onCollide(Event.spawn("embers", 1).chance(0.3f)),
                Over.life().color(SPARK_GRADIENT).size(SHRINK))
        .render(Billboard.velocityAligned().stretch(0.04f).texture(SPARK).additive().soft(0.05f)))
    .emitter("embers", e -> e
        .capacity(200).spawn(Spawn.fromEvents())                 // the parent's position, velocity and color
        .init(Init.inherit(Attribute.POSITION, Attribute.COLOR), Init.inheritVelocity(0.3f),
              Init.lifetime(Range.of(0.8, 1.5)))
        .update(Forces.gravity(0.3f), Noise.curl(1.5f, 0.8f), Over.life().alpha(FADE))
        .render(Billboard.facing().flipbook(EMBERS, 4, 4).alphaToAdd(0.7f)))
    .build();
AssetRef<EffectData> SPARKS = sparks.ref();

// Playing effects: handles made once and reused; per-frame calls take primitives and allocate nothing.
Effects effects = new Effects(scene);
Effect torch = effects.play(TORCH_FIRE, torchNode);             // attached: follows the node
torch.set(INTENSITY, 0.8f);                                     // a user parameter, any frame
torch.stop();                                                   // stops spawning; particles live out their lives
torch.kill();                                                   // gone now

Effect trail = effects.play(SWORD_TRAIL, a.node(handR))         // joints are scene nodes
    .offset(0, 0.4, 0).inheritVelocity(1f);

effects.spawn(SPARKS, x, y, z, nx, ny, nz);                     // fire and forget, world space, pooled
effects.burst(BULLET_IMPACTS, x, y, z, nx, ny, nz, material);   // into one shared instance (Data Channel style)

animators.listen(FOOTSTEP, (node, event, weight) ->             // animation events trigger effects
    effects.spawnAt(DUST, footNodeOf(node)));
double blink = a.curve(BLINK);                                   // animation curves drive parameters
torch.set(INTENSITY, (float) blink);

// Engine side, through the plugin or by hand:
EffectSync sync = new EffectSync(resources, effects, particles);  // follows Effects, like SceneSync
graph.add(new ParticleSimulation(particles));                     // the frame's simulation
graph.addPerView(port -> new ParticleDraw(particles, sceneViews.of(port)));
```

**Gameplay-relevant particles** (§4.19): a game system simulates them on the CPU and writes their records each frame
with the generated `ParticleWriter` (`CpuParticles.writer(emitter)`: `position(x, y, z).color(...).size(...)`). They
are drawn exactly like GPU particles. The simulation never reads them back.

**Composition:**
- An effect is many emitters with shared user parameters.
- Each emitter has a delay, a duration, loops and a loop delay. These give timing offsets and sequences (a flash at
  0 s, debris at 0.05 s, smoke from 0.1 s for 3 s).
- Prewarm runs the emitter forward a given time, in fixed steps, when the instance starts (looping ambience starts
  full). It is capped, with a warning in the statistics when it costs too much.
- Templates: `EmitterData.from(template).with(...)` overrides inherited settings, like Niagara's inheritance but on
  values.
- Per-instance overrides are user parameters only. Changing structure means another asset.

### 2.7 How it works

- **Emission shapes**, as initializers that set position (and optionally velocity and normal):
  - point; sphere and shell (radius, thickness, arc); box and box surface; cone (angle, radius, length, from base or
    volume); circle, disc and ring; line, edge and polyline; torus;
  - **mesh surface, vertices or volume** of a static mesh, from the `GeometryPool`'s data by the instance's mesh number:
    an alias table over triangle areas, built once per mesh when an effect first uses it;
  - **skinned mesh surface** reading the *deformed copy* the `Deformation` pass writes (A1 of animation). Particles
    born on a running character's skin are where the skin is this frame. The mesh shape samples the rest-pose areas;
  - **texture or density map** projected on a shape (a burning logo, a mask), by an alias table over texels;
  - **SDF and volume** (rejection sampling in a 3D texture), later with SDFs.
- **Sockets:** an emitter attaches to a node, so a joint, a child node at a muzzle, or a model's named node serve as
  sockets.
- **Updaters**, in the order listed:
  - forces: gravity, drag (linear and quadratic), wind (a world wind parameter plus gusts), vortex, point attractor and
    repeller (falloff, kill radius), line attractor, orbit (angular velocity around an axis), curl noise (3D, animated,
    octaves), vector field (a 3D texture in a box, tiled or clamped), and attractor shapes from the scene (below);
  - limits: speed limit, force limit, speed by curve;
  - attributes over life, by speed or by parameter: size, color and alpha, rotation (and rotation speed), flipbook
    frame, light intensity;
  - **neighbour grids** for flocking and separation, later: a grid simulation stage as Niagara does it (clear, insert,
    query), when someone needs it.
- **Colliders and attractors** are scene objects too: `EffectColliders` on nodes (plane, sphere, box, capsule, and a
  height field from `TerrainHeights` around the camera). Effects use them by layer mask (Godot). Collision response is
  bounce (restitution, friction), stick (attach to the surface), slide, or kill. Each can send a collision event.
  - **Depth-buffer collision** uses the primary view's previous-frame depth, reprojected (the simulation runs before
    this frame's draws). It is cheap and wrong off screen and behind occluders. In VR it uses the left eye or the
    pair's center.
  - **Height field:** a texture filled from `TerrainHeights` in tiles around the camera as they load. It is right off
    screen, outdoors.
  - **Ray queries** against the TLAS the engine already builds (`RayTracing`, inline `rayQuery`), on GPUs that have it:
    exact, off screen too, one segment per particle per frame. A later step, after measurement; Niagara's ray-traced
    collision proves the idea.
  - **Ground snapping** (decals, puddles, leaves settling) is a collision mode that places a particle on the hit surface
    and freezes it.
- **Events and sub-emitters:**
  - An emitter declares events (birth, death, collision, periodic, or a condition such as speed below a value). Each
    names the emitter it spawns from, a count (or a range, or a chance) and what is inherited.
  - The `update` pass appends `{target emitter instance, parent index into copy B, count, salt}` with an atomic counter
    into `PARTICLE_EVENTS` (capacity from the effect, overflow counted and dropped).
  - The next spawn pass reads the parent's attributes straight from copy B (VFX Graph's "source attributes"). There is
    no payload struct to declare.
  - The child's seed is `hash(parentSeed, event salt, i)`, so children look the same whatever order the atomics took.
  - **Nesting:** each level adds a frame. The depth is limited (4 by default) and cycles are rejected when an effect is
    built.
  - **CPU-side events** (an emitter that should start a whole effect, a sound or gameplay) are not read back from the
    GPU. They are CPU events from the emitter runtime (start, loop, end), and the game triggers them directly.
- **Timing:**
  - Normalized age = `age / lifetime` drives curves (the curve atlas row, sampled linearly).
  - Spawn timing is exact within a frame (birth times along the frame); the spawn pass integrates the remainder.
  - Simulation time is the world clock: once per rendered frame, delta clamped (0.1 s). Emitters opt into fixed
    substeps (`substeps(2)` or `fixedRate(60)`) for stiff forces and collisions, as Godot's fixed fps and PopcornFX's
    micro-updates do.
  - Effects beyond their distance or culled may update at a lower rate (every 2nd or 4th frame, with delta summed).
- **Bounds:**
  - the effect's authored bounds (or bounds from its shapes, speeds and lifetimes, estimated when it is built) for
    CPU significance and culling;
  - GPU bounds, computed in `update`, for drawing (per-view culling of a whole emitter instance) and for mesh particle
    instances.
  Nothing waits for a readback.
- **Scalability:**
  - `effects.quality` (low, medium, high, epic) scales spawn counts and capacities per effect type and disables
    emitters marked for a minimum quality.
  - The global pool budget comes in bytes. Near the budget, the least significant instances get spawn scaling, then
    culling.
  - Distance gives fewer drawn particles (a stable subset) with larger sizes.
  - Half-resolution drawing is per emitter or for all particles at low quality.
  - Instance caps per effect type, with significance kept every frame (Niagara: without a significance handler, caps
    act only at spawn).
- **Pooling:** effect instances are slots in `Effects` (generations, ids reused). Ranges come back to the allocator once
  no frame in flight reads them. `effects.spawn(...)` reuses stopped instances of the same asset.

### 2.8 Networking and determinism

- **Effects are cosmetic.** A server never simulates particles. It replicates *what plays*: effect asset id, node (or
  world pose), seed, start tick, user parameters at start, stop and kill. These are `Effects` changes in the change
  log, sent like any other scene change. Gameplay never depends on GPU particles (§4.19).
- **The same look on every client:**
  - the same seed;
  - spawn counts from the deterministic CPU runtime (integer ticks of the world clock, `StrictMath` where it decides
    counts);
  - per-particle random values from hashes, independent of ordering.
  On the same GPU and driver, the same inputs give the same frames. Across GPUs the floats differ (fused multiply-add,
  transcendental functions): close, not bit-identical.
- **Late joiners and replays:** the start tick lets a client prewarm a looping effect to "now" (capped), and skip
  one-shot effects that have already ended.
- **Replays** record effect events, not particles.

---

## 3. What changes in existing code

- **`GpuScene`:** a *GPU-written range* of instance ids. The CPU never claims them, the upload never scatters into them,
  and the instance pass culls up to the count the GPU writes (indirect). Occlusion history and layers work as for any
  id.
- **`SceneGeometry` / `GeometryProvider`:** review item 9 (`SceneGeometry.renderer()` uses `instanceof` over the two
  providers) must be fixed before opaque or masked billboards become a provider. Mesh particles need no provider
  (they are instances).
- **Transparency:** `ParticleDraw` orders itself `at(TRANSPARENT).after(Transparency.ID)`. Emitters opting into
  order-independence draw through `GeometryProvider.drawBlended`-like hooks into `BlendedTargets` (`Kind.WEIGHTED`), so
  `BlendedTargets` gains a hook for non-meshlet contributors. A test runs three frames or more (pitfalls: per-phase
  lists).
- **`Keys`:** `PARTICLES` and `PARTICLE_EVENTS` buffers. `Keys.SCENE_DEPTH` history of the primary view for depth
  collision (`g.historyBuffer`/`g.history`).
- **`Lights`** (later): a GPU-written light range for particle lights; `ClusteredLights` reads a GPU count.
- **Per-instance custom data** (tint, alpha per mesh particle): the "buffers by id" README promises.
- **frame-graph-v2 §4.19** is refined, not reversed:
  - mesh particles become GpuScene instances instead of a `GeometryProvider`;
  - "blended particles share the transparency path" becomes P8;
  - the rest stands (GPU simulation, spawn ring, CPU-fed emitters, extension by modifying particle keys, fixed render
    methods, budgets).
- **Application API:** effects advance in the effect sync after `LATE`, on the world clock (D88, D128). No new phase
  is needed.

---

## 4. Options weighed (the decisions are E1 to E22 above)

**P1. Where particles simulate**
1. **GPU only; gameplay particles CPU-fed into the same renderers** (recommended). One simulator per behaviour, as
   §4.19 decided. Cons: no CPU fallback for GPUs without compute (none at Vulkan 1.3); authors can't make a particle
   gameplay-relevant without code.
2. CPU and GPU emitters, one description (Niagara, Shuriken plus VFX Graph). Pros: gameplay reads particles; tools
   preview without a GPU. Cons: every module twice, behaviour drift, tests doubled.
3. CPU only, uploaded each frame. Pros: deterministic, simple. Cons: about 100k particles at best; per-frame upload
   bandwidth; not what modern effects need.

**P2. Where emitter logic runs**
1. **CPU, in `scene-effects`** (recommended). Pros: deterministic, testable, replicable; the game's inputs are on the
   CPU; Niagara, VFX Graph and Godot agree. Cons: spawn records cross to the GPU each frame (a few bytes per emitter).
2. GPU (spawn counts computed by a pass). Pros: nothing per emitter on the CPU. Cons: LOD, timing and events from the
   game need GPU state anyway; harder to test; no server side.

**P3. How effects are described**
1. **Data (`EffectData`) from a fixed module set, plus Slang-registered module types** (recommended). Pros: like
   materials; files and an editor later; no per-effect compile. Cons: a fixed set limits artists until plugins add
   modules.
2. A node graph compiled to shaders (Niagara, VFX Graph, Frostbite). Pros: unlimited expressiveness. Cons: a shader
   compiler in the runtime or build, permutations, slow iteration; a graph editor to build.
3. Code-built effects only (Java lambdas). Pros: quick to start. Cons: the GPU cannot run Java; data for tools and
   servers is lost.

**P4. Shader strategy**
1. **One uber-kernel interpreting module programs; 64-slot chunks per emitter** (recommended). Pros: effects load
   with nothing to compile; any number of emitters in one dispatch; uniform branches per wave. Cons: interpretation
   overhead (a loop over opcodes; mitigated by specialization: the program is uniform per wave); register pressure of
   the largest module.
2. Per-effect generated Slang, compiled at build or load time. Pros: fastest per particle. Cons: needs slangc at
   runtime or ahead-of-time effect builds; pipeline counts grow; slow iteration.
3. A fixed-function kernel with flags (Godot's process material). Pros: simplest. Cons: grows into an unmaintainable
   uber-shader; plugins can't add behaviour.

**P5. Pool management**
1. **Order-preserving compaction (count → scan → update and scatter, ping-pong)** (recommended). Pros: dense alive
   ranges (simple draws and dispatches); spawn order kept (ribbons, stable LOD subsets); deterministic order; the
   update writes each particle once either way. Cons: two buffers (2 × 64 B × capacity); a small scan pass.
2. Dead list plus alive list (AMD, Wicked). Pros: one buffer, particles never move. Cons: alive-list order is
   atomic-order (nondeterministic); ribbons need a sort; free-list maintenance.
3. Also later: stateless analytic emitters (Niagara lightweight) as a second emitter kind for ambient effects. Pros:
   no memory, no update. Cons: a restricted module set; a second path.

**P6. Capacity**
1. **Reserved per instance at play, from the authored maximum** (rate × lifetime + bursts, or set explicitly)
   (recommended). Pros: no spawn ever fails mid-effect; predictable (Godot's `amount`). Cons: memory reserved for the
   worst case.
2. One shared pool, allocated per frame by need. Pros: memory follows use. Cons: an emitter can starve others mid
   effect; allocation on the GPU, harder to debug.
3. Grow on demand per instance. Pros: no authoring of capacity. Cons: frames of missing particles while the range
   moves; fragmentation.

**P7. Sorting**
1. **Per view (per stereo pair), radix sort of sorted emitters only; emitters ordered by distance as wholes**
   (recommended). Pros: correct within an emitter, cheap for additive-only effects (unsorted); one sort for both eyes.
   Cons: two overlapping emitters don't interleave per particle (same as VFX Graph).
2. One global per-particle sort across all emitters. Pros: correct interleaving. Cons: renderers (billboards, ribbons,
   trails) would have to interleave in one draw stream, so one uber-pipeline for all of them; sorts every particle,
   also those of additive emitters that need no order.
3. Bitonic sort. Pros: simple. Cons: O(n log² n), slower past about 64k keys.
4. No sorting; all particles through WBOIT. Pros: no sort, order-independent. Cons: additive and very opaque
   particles look wrong; WBOIT weights tuned for glass.

**P8. How blended particles join transparency**
1. **Their own sorted, premultiplied pass after the blended materials; per-emitter opt-in into WBOIT**
   (recommended). Pros: correct additive and alpha; a smoke column behind a window can opt into WBOIT. Cons:
   particles draw over glass in front of them unless they opt in.
2. Everything into WBOIT. Pros: one composite, order-independent with glass. Cons: additive breaks (accumulated alpha
   0); smoke's dense cores wash out.
3. A per-object sort mixing blended meshes and particle emitters (Unreal). Pros: usually right. Cons: needs sorted
   blended meshes; the engine has WBOIT and no sorted mesh path.

**P9. Sub-emitters and events**
1. **GPU event buffer; children spawn at the next spawn pass, reading the parent's attributes; one generation per
   frame** (recommended). Pros: GPU-native; no payload declaration; bounded; deterministic look by seeds. Cons: each
   nesting level costs a frame; overflow drops events (counted).
2. Same-frame chains (a spawn pass per generation). Pros: no latency. Cons: a pass per level per frame; more barriers.
3. Events through CPU readback (Niagara's CPU events). Pros: events reach gameplay. Cons: 2 to 3 frames late,
   nondeterministic; ruled out by §4.19.

**P10. Collision**
1. **Analytic scene colliders + terrain height field + previous-frame depth; ray queries later** (recommended). Pros:
   covers ground, walls near the player and authored shapes; cheap; all on a 1.3 GPU. Cons: depth is wrong off screen;
   no arbitrary geometry until ray queries.
2. A global SDF of the scene. Pros: smooth, off screen, also for attraction. Cons: a whole system to build and keep
   updated (clipmaps, mesh SDFs).
3. Ray queries only. Pros: exact. Cons: only where ray tracing exists; cost per particle.
4. CPU physics (common-collision) for CPU particles only. Pros: exact, gameplay. Cons: not for GPU particles.

**P11. Determinism**
1. **Deterministic inputs and seeds; the same frames on the same GPU and driver; close across GPUs**
   (recommended). Pros: replays and clients look alike; testable against tolerances. Cons: not bit-exact across
   vendors.
2. Bit-exact everywhere (fixed point, no atomics, fixed step). Pros: lockstep-safe. Cons: slow, restrictive, and
   unnecessary for cosmetics.
3. None. Pros: simplest. Cons: tests flaky; clients diverge visibly (same effect, different burst).

**P12. Time step**
1. **Per rendered frame on the world clock, clamped; per-emitter fixed substeps** (recommended). Pros: smooth at any
   frame rate; cheap. Cons: different frame rates give slightly different paths.
2. Fixed steps always, interpolated for drawing (Godot's fixed fps plus interpolation). Pros: stable, deterministic
   paths. Cons: the previous state must be kept (memory); interpolation in every renderer.
3. Variable with no clamp. Cons: explodes after a hitch.

**P13. Ribbons and trails in the first version**
1. **No: billboards first; trails (per-particle history) and ribbons (spawn order) in step 10** (recommended). Pros:
   the core lands sooner; compaction already keeps the order. Cons: no sword trails at first.
2. Yes, from the start. Pros: complete. Cons: a larger first step, renderer and LOD details before the core is proven.

**P14. Lit particles**
1. **Unlit and emissive first; lit per vertex from the light list with sun shadow next; per-pixel normal-mapped and
   six-way after** (recommended). Pros: the cheap path first; six-way gives the best smoke for the cost. Cons: smoke
   looks flat at first.
2. Per-pixel lit from the start. Pros: quality. Cons: overdraw × light loop is the worst cost on a headset.
3. Froxel injection (Frostbite). Pros: the best volumetric look. Cons: needs volumetric fog the engine doesn't have.

**P15. Mesh particles**
1. **GPU-written instances in a reserved `GpuScene` range** (recommended). Pros: culling, cluster LOD, shadows,
   visibility buffer, materials, ray tracing all free. Cons: per-particle tint needs per-instance custom data; the
   instance pass scans the reserved range (bounded by the GPU count).
2. Their own instanced draw. Pros: independent. Cons: duplicates culling, LOD, shading and shadows.

**P16. Bounds and culling**
1. **Authored or estimated bounds on the CPU; GPU-computed bounds for drawing** (recommended). Pros: nothing waits for
   a readback; drawing culls tightly. Cons: authored bounds can be wrong (a debug overlay shows them).
2. GPU bounds read back for the CPU. Pros: no authoring. Cons: frames late; a moving effect pops.
3. Fixed bounds only. Pros: simplest. Cons: loose culling, wasted draws.

**P17. Precision and space**
1. **Float offsets from a double-float origin per instance, rebased when far** (recommended). Pros: precise anywhere,
   like instances; nothing rewritten per frame. Cons: a rebase pass for long-travelling world-space emitters.
2. Camera-relative positions rewritten each frame. Pros: no origins. Cons: every particle rewritten when the camera
   moves; several views disagree.
3. Plain world floats. Pros: simplest. Cons: visibly wrong far from the origin (README measured this for instances).

**P18. Curves and gradients**
1. **Baked into a curve atlas texture, sampled by normalized age** (recommended). Pros: constant cost; any key count;
   shared by instances. Cons: 256 samples smooth sharp steps (a step curve can be flagged nearest).
2. Keys evaluated in the shader. Pros: exact. Cons: a loop per curve per particle.

**P19. VR orientation and sorting**
1. **One sort and one billboard orientation per stereo pair, from the pair's center** (recommended). Pros: no stereo
   rivalry (each eye seeing a sprite turned differently); half the sorting. Cons: needs the graph to know which views
   form a pair (`Headset` makes both eyes: it can name the pair).
2. Per eye. Pros: no new concept. Cons: sprites shimmer between eyes near the camera; double sort cost.

**P20. Off-screen and far effects**
1. **Per effect type: keep simulating within distance (at a reduced rate), pause or kill beyond** (recommended). Pros:
   no fires that restart when looked at; cheap far away. Cons: authors choose per type.
2. Always simulate. Cons: cost of every effect in the world.
3. Pause when not visible (Godot by AABB). Cons: visible restarts and frozen smoke.

**P21. Where the scene side lives**
1. **A new module `scene-effects`** (recommended). Pros: servers take it without render data; mirrors
   `scene-animation`. Cons: one more module.
2. Inside `scene-render`. Pros: fewer modules. Cons: servers replicating effects pull render data.

**P22. Gameplay-driven bursts (impacts, footsteps)**
1. **`effects.burst(...)` into one shared instance per effect asset** (Niagara Data Channels) (recommended). Pros: a
   thousand impacts are one emitter range, one draw. Cons: shared capacity (the oldest particles die first when full);
   all bursts share user parameters (per-burst values travel in the spawn record: position, normal, a color or
   material id).
2. An effect instance per impact. Pros: simple. Cons: instance, range and draw per impact; caps hit quickly.

---

## 5. Steps (a PR each, tested, with the sample content that checks it)

| # | Repo | What | Checked with |
|---|---|---|---|
| 1 | scene | `scene-effects`: `EffectData`, emitters, module records by name, curves, gradients, ranges, bindings, `EffectType`, validation (cycles, nesting depth, unknown modules); `Effects` store with change log | Device-free tests: building, equality (`ref()`), validation errors with suggestions, change log order |
| 2 | scene | `EffectRuntime`: delay, duration, loops, rates with carried fractions, bursts across loop ends, per-distance spawning, birth times, prewarm counts, significance and caps, quality scaling, seeds | Device-free: counts per frame against closed forms at 30, 60, 90 and 144 Hz and with hitches; same counts on any thread count; zero bytes allocated per frame |
| 3 | engine | Pool, generated `Particle`, emitter table, spawn ring, curve keys in a buffer (searched per particle, E18), module programs; count → scan → update → spawn → args; first modules (shapes: point, sphere, box, cone, disc; lifetime, velocity, gravity, drag, over life: size, color); world and local space (E17) | lavapipe and the RX 9070 XT: ballistic positions against analytic ones; spawn counts and order; overflow counted; growth of the pool; seeds give equal values in any order; synchronization validation clean |
| 4 | engine | `ParticleDraw`: per-view cull, camera, velocity and axis billboards, premultiplied alpha and additive, soft fade, flipbooks with frame blending, unlit; trails (history per particle), ribbons (spawn order, ribbon ids) and beams (E13); at `TRANSPARENT` after WBOIT | Screenshot tests (sprite coverage, soft edge against a wall, additive over dark); a ribbon behind a moving emitter, continuous across compaction; a beam between two nodes; three-frame test with occlusion phases; dynamic resolution region; one thread and four threads draw the same image |
| 4b | scene, engine | CPU emitters (E1): the same modules simulated on worker threads, uploaded as records into the same pool and drawn alike; gameplay reads their particles | A CPU and a GPU emitter of one asset draw the same image within tolerance; reading a CPU emitter's particles from game code; allocation-free per frame |
| 5 | engine-scene, app | `EffectSync`, attachment to nodes and joints, world and local space, inherit velocity, origin rebasing, stop and kill, `Effects` plugin; demo effects: torch fire, sparks, smoke, footstep dust from the Fox's walk events | Fox walking with dust at its feet (animation events); an effect 7,000 km out draws like at the origin; zero bytes allocated in the scene benchmark's column |
| 6 | engine | Sorting: indirect radix sort per view, sorted emitters only, one sort per stereo pair, centre-facing billboards | Two overlapping colored emitters in front-to-back and back-to-front order; `StereoBench` with particles: both eyes identical orientation; sort time measured at 10k, 100k and 1M |
| 7 | engine | Forces and collision: curl noise, vortex, attractors, point and line forces, orbit, vector fields (3D textures), speed limits; collision chosen per emitter (E10): analytic colliders on nodes, terrain height field, previous-frame depth, ray queries where the device traces rays, the physics scene for CPU emitters; bounce, stick, slide, kill | Analytic checks (orbit radius kept, bounce height by restitution); particles resting on the terrain from `TerrainHeights`; rain stopped by a roof |
| 8 | engine | Events and sub-emitters: birth, death, collision, periodic; inheritance from source attributes; nesting depth; `effects.burst` into a shared instance or as instances of their own (E22) | Fireworks (burst on death of a burst); impact sparks from 1,000 bursts in a frame in one draw; dropped events counted, not crashing |
| 9 | engine | Mesh and skinned emission: static surface, vertices, volume by alias tables; the deformed copy of a skinned mesh; texture and density masks | Particles uniform over a mesh's area (histogram test); fire on BrainStem's or the Fox's skin as they move |
| 10 | engine | Trail and ribbon polish on top of step 4: texture modes (stretch, tile), width over length, sword swooshes attached to joints | Sword swoosh on the Fox's or RiggedFigure's hand joint; tracer sparks |
| 11 | engine | Mesh particles as GPU-written `GpuScene` instances (reserved range, indirect count), with shadows and the visibility buffer | Debris rocks casting shadows; `VisibilityBufferShading` and forward draw the same image; occlusion history over 3+ frames |
| 12 | engine | Lighting chosen per emitter (E14): per-vertex light list and sun shadow; per-pixel normal-mapped; six-way lightmaps | 1,024 point lights screenshot (`Screenshots.renderLights` style); smoke lit from one side; cost measured on both shading techniques |
| 13 | both | Scalability: effect types enforced on the GPU side, budgets, distance LOD (stable subsets), update rates, half-resolution particles with depth-aware upsample, `effects.quality` | `ParticleBenchmark` (1M particles: update, sort, draw; overdraw scene of large smoke sprites) on the RX 9070 XT and at Quest 3 resolution through `StereoBench`; README tables dated |
| 13b | engine | GPU-side emitters (E2): emitter state on the GPU for huge emitter counts, driven by GPU events (particles as emitters at scale) | 100,000 emitters from one GPU event burst; counts and timing against the CPU runtime's for the same asset |
| 14 | engine | Plugin modules: `[ParticleModule]` Slang structs registered like material types (generated records, name ids, the uber-kernel's switch generated); `modify(PARTICLES)` techniques | A game's own force module in a test project, as `CustomPassTest` does for passes |
| later | engine | Distortion, particle lights, decals, neighbour grids and simulation stages, SDF collision and emission, stateless emitters, froxel injection | Each with its own design note when the engine has the system it depends on |

Each engine step keeps `docs/pitfalls.md` in mind:
- per-frame Vulkan calls go through `Commands`;
- buffer pointers are loaded one at a time (the integrated GPU);
- a layout shared by Java and Slang is generated once;
- tests run with validation, on lavapipe too, over three or more frames;
- results that come from the GPU are asserted after `waitIdle`;
- the scene benchmark's bytes-allocated column stays 0.

---

## 6. Sources

**Unreal Niagara**
- [Overview](https://dev.epicgames.com/documentation/en-us/unreal-engine/overview-of-niagara-effects-for-unreal-engine)
- [Simulation stage API](https://dev.epicgames.com/documentation/en-us/unreal-engine/API/Plugins/Niagara/UNiagaraSimulationStageGeneric)
- [Data interface API](https://docs.unrealengine.com/4.27/API/Plugins/Niagara/UNiagaraDataInterface/index.html)
- [Scalability settings](https://dev.epicgames.com/documentation/en-us/unreal-engine/python-api/class/NiagaraSystemScalabilitySettings)
- [Effect types (forum)](https://forums.unrealengine.com/t/niagara-scalability-effect-types/264959)
- [Data channels](https://dev.epicgames.com/documentation/en-us/unreal-engine/niagara-data-channels-overview)
- [Lightweight emitters](https://dev.epicgames.com/documentation/en-us/unreal-engine/niagara-lightweight-emitters-overview)
- [GPU ray tracing collisions](https://dev.epicgames.com/documentation/en-us/unreal-engine/gpu-raytracing-collisions-in-niagara-for-unreal-engine)
- [Determinism and seed](https://dev.epicgames.com/documentation/en-us/unreal-engine/python-api/class/VersionedNiagaraEmitterData?application_version=5.1)
- GPU events: [UE-64938](https://issues.unrealengine.com/issue/UE-64938),
  [forum](https://forums.unrealengine.com/t/niagara-cpu-emitter-read-particle-location-from-gpu-emitter/1268115),
  [ribbon gaps](https://realtimevfx.com/t/fixing-first-frame-gaps-in-niagara-ribbons/29170)

**Unity VFX Graph and Shuriken**
- [Contexts](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.5/manual/Context.html)
- [Events](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph%4012.1/manual/Events.html)
- [GPU Event](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/Context-GPUEvent.html)
- [Output settings and sorting](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/Context-OutputSharedSettings.html)
- [Six-way lighting](https://docs.unity3d.com/Packages/com.unity.visualeffectgraph@17.0/manual/six-way-lighting.html)
- [Shuriken sub emitters](https://docs.unity3d.com/Manual/PartSysSubEmitModule.html)

**Godot 4**
- [Particle system properties](https://docs.godotengine.org/en/stable/tutorials/3d/particles/properties.html)
- [Sub-emitters](https://docs.godotengine.org/en/stable/tutorials/3d/particles/subemitters.html)
- [Collision](https://docs.godotengine.org/en/stable/tutorials/3d/particles/collision.html)
- [Attractors](https://docs.godotengine.org/en/stable/tutorials/3d/particles/attractors.html)
- [GPUParticles3D class](https://docs.godotengine.org/en/4.2/classes/class_gpuparticles3d.html)

**PopcornFX**
- [Hands-on review](https://gamefromscratch.com/popcornfx-particle-creation-software-and-run-time-hands-on/)
- [80.lv interview](https://80.lv/articles/innovating-vfx-in-gaming-the-game-changing-journey-of-persistant-studios/)
- [v2 discussion](https://realtimevfx.com/t/popcornfx-v2-tests-templates-discussions/6108)

**Studio talks**
- AMD, Thomas 2014: [slides](https://www.slideshare.net/slideshow/holy-smoke-faster-particle-rendering-using-direct-compute-by-gareth-thomas/35656271)
- [Wicked Engine](https://wickedengine.net/2017/11/gpu-based-particle-simulation/)
- Frostbite: [Emitter Graph](https://www.ea.com/frostbite/news/frostbite-gpu-emitter-graph-system),
  [unified volumetrics](https://www.ea.com/news/physically-based-unified-volumetric-rendering-in-frostbite)
- Bungie: [Destiny particles](https://80.lv/articles/siggraph-the-destiny-particle-architecture/)
- Naughty Dog: [Kovalovs 2020](https://history.siggraph.org/?p=76957),
  [Ikeda 2021](https://gdcvault.com/play/1027072/Creative-and-Experimental-VFX-in)
- Guerrilla: [Valient 2014](https://www.guerrilla-games.com/media/News/Files/GDC2014_Valient_Killzone_Graphics.pdf),
  [postmortem](https://www.guerrilla-games.com/media/News/Files/Valient_Killzone_Shadow_Fall_Demo_Postmortem.pdf),
  [procedural placement](https://www.gdcvault.com/play/1024120/GPU-Based-Run-Time-Procedural)
- [Coffin 2018](https://www.gdcvault.com/play/1025068/Beyond-Emitters-Shader-and-Surface)

**Tools and libraries**
- [bevy_hanabi](https://github.com/djeedai/bevy_hanabi)
- [FidelityFX Parallel Sort](https://github.com/GPUOpen-Effects/FidelityFX-ParallelSort)

**Rendering**
- [Soft particles](https://developer.download.nvidia.com/whitepapers/2007/SDK10/SoftParticles_hi.pdf)
- [Off-screen particles](https://developer.nvidia.com/gpugems/gpugems3/part-iv-image-effects/chapter-23-high-speed-screen-particles)
- [WBOIT](https://jcgt.org/published/0002/02/09/)
- [Motion-vector flipbooks](http://www.klemenlozar.com/frame-blending-with-motion-vectors/)
- [Curl noise](https://www.cs.ubc.ca/~rbridson/docs/bridson-siggraph2007-curlnoise.pdf)

**Caveats**
- PopcornFX's own documentation could not be reached, so its section relies on secondary sources.
- No public Ubisoft particle talk was found.
- The Wicked Engine and Destiny details are from the article and summaries, not the original slides.
- Claims about the current state of Niagara GPU events come from issue reports and forums, not official
  documentation.
