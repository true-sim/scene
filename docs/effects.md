# Particles and effects: design

Effects for games: sparks, smoke, fire, dust from footsteps, muzzle flashes, magic, rain, debris, trails and beams.
They are described as data in the scene library, simulated per emitter on the GPU (the bulk) or on the CPU (small
effects and particles gameplay reads), and drawn by the engine's frame graph in every view, both eyes of a headset
included. Decided with the user one question at a time (E1 to E22 below), after a survey of Unreal Niagara, Unity VFX
Graph and Shuriken, Godot 4, PopcornFX, Houdini, bevy_hanabi and the GPU particle talks of Frostbite, Bungie, Naughty
Dog, Guerrilla and AMD (sources at the end), and from a reading of engine `README.md`, `docs/frame-graph-v2.md` (§4.18,
§4.19, §4.20), `docs/application-api.md` (D88 to D128), `docs/pitfalls.md`, `engine-render` (`GeometryPool`,
`GpuScene`, `SceneData`, `Deformation`, `RayTracing`, `DeformedBottomLevels`, `BottomLevelUpdates`, `Transparency`,
`WeightedBlendedTransparency`, `BlendedTargets`, `GeometryProvider`, `Lights`, `ClusteredLights`), engine-scene
`SceneSync`, and scene `README.md`, `docs/animation.md`, `scene-animation` and `scene-render`. Then reviewed at high
effort against the code and the cited sources (section 6 lists what the review changed and the risks left).

The decisions are E1 to E22. Sections 2 and 3 are written for them; section 4 keeps the options weighed (P1 to P22)
with the reasons, and marks where the decision differs from the first recommendation.

## Decisions made

| | Decision | Notes |
|---|---|---|
| E1 | **CPU and GPU emitters**: each emitter simulates on the GPU (the default, millions of particles) or on the CPU (worker threads; gameplay can read them; small effects skip dispatches); both draw through the same renderers. | Differs from the recommendation (GPU only plus CPU-fed records). How: §2.9. |
| E2 | **Emitter logic on the CPU** in `scene-effects` (timing, loops, rates, bursts, distance spawning, LOD; testable, servers run it); **particles as emitters at scale on the GPU through events** (E9): particles that emit spawn children every frame from the event buffer, so no second emitter runtime exists. | Revised after the review: GPU-side emitters through events, not a GPU emitter table. How: §2.9. |
| E3 | Effects as **data**: `EffectData` assets of emitters built from a fixed set of modules, values by name; plugins register Slang modules. | As recommended. |
| E4 | **One interpreting kernel**, 64-slot chunks per emitter. | As recommended. A few compiled variants of it by capability (§2.8). |
| E5 | **Survivors copied in order** between two buffers (spawn order kept: deterministic, ribbons). | As recommended. |
| E6 | Capacity **reserved per instance** from its authored maximum. | As recommended. |
| E7 | **Radix sort per view** (per pair of eyes), sorted emitters only, emitters ordered as wholes. | As recommended. |
| E8 | Blended particles in **their own sorted premultiplied pass** after blended materials; order-independent blending per emitter. | As recommended. |
| E9 | Sub-emitters by **GPU events, one generation per frame**. | As recommended. Events sorted before spawning (§2.7), so E5 and E11 hold. |
| E10 | **Collision chosen per emitter**: analytic colliders on nodes, the terrain height field, last frame's depth, ray queries against the ray tracing structures (where the device traces rays), the physics scene for CPU emitters; an SDF later as another choice. | Differs: choosable, not one fixed set. A fallback chain per emitter (§2.7). |
| E11 | **Identical on the same GPU and driver**, close across GPUs. | As recommended. |
| E12 | **Once per frame**, clamped; fixed substeps per emitter on request. | As recommended. |
| E13 | **Ribbons, trails and beams in the first version**, with billboards. | Differs: in v1, not later. |
| E14 | **Lighting chosen per emitter**: unlit, per vertex (light list, sun shadow), per pixel (normal maps), six-way lightmaps; froxel injection later as another choice. | Differs: choosable. |
| E15 | Mesh particles as **GPU-written `GpuScene` instances**. | As recommended. What it costs in `GpuScene` and ray tracing: §3. |
| E16 | **CPU bounds** (authored or estimated) for culling and LOD, **GPU bounds** for drawing. | As recommended. |
| E17 | **Float offsets from a high-precision origin per instance**, rebased when far; each emitter simulates in **world space** (particles stay where born) or **local space** (they move with the emitter's node or joint). | World and local space added by the user. |
| E18 | Curves and gradients as **keys evaluated in the shader** (searched per particle), not baked. | Differs: keys, not an atlas. Cost kept down as §2.7 says. |
| E19 | **One sort and orientation per pair of eyes** in VR. | As recommended. |
| E20 | Off-screen and far behavior **per effect type**. | As recommended. |
| E21 | A new module **`scene-effects`**. | As recommended. |
| E22 | **Gameplay bursts chosen per call**: into one shared instance per asset (thousands of hits, one draw) or as instances of their own (each movable and stoppable). | Differs: choosable. Shared instances are per asset *and cell* (§2.7). |
| E23 | **Shared simulations**: an effect whose modules don't depend on where it is simulates K variants (own seeds and time offsets) and draws them at many placements, each a transform and a variant; eligibility comes from its modules; near placements may keep simulations of their own (step 18a). | Added by the user after step 9. |
| E24 | **Particle modules are generated from their Slang structs**: a module, built-in or a game's, is one `[ParticleModule("name")]` struct (its fields its parameters, its functions its stages), plus an optional Java twin; the build numbers every module of a project and its libraries and generates the kernel's dispatch, the opcodes and the Java listing. Slang is the source; scene-effects keeps a mirror of the built-in types for use without the engine, which the engine checks. | Added after step 20, which wired modules by hand. How: §2.13. |

**Reviewer notes on decisions** (from the review; the user's answers to them are at the end of §6):

- **Reviewer note on E1.** CPU emitters mean every built-in module exists twice, in Java and in Slang, and
  frame-graph-v2 §4.19 chose CPU-fed records precisely to avoid "a second simulator". The cost is real but bounded if
  (a) the CPU module set is a *declared subset* (shapes, forces, curves, analytic and physics collision; no depth or ray-query
  collision, no skinned-mesh emission, no GPU-only plugin modules), checked when an effect is built, and (b) the Java
  simulator is written first and serves as the *reference* that GPU tests compare against (step 3), so the second
  implementation pays for itself in testing. Alternative: GPU emitters only, plus §4.19's game-written records, and
  the Java simulator kept as a test oracle and server tool only.
- **Reviewer note on E2.** A second emitter runtime written in Slang (timing, loops, bursts, LOD) would duplicate
  `EffectRuntime` and never match it exactly. The review recommends building "emitter logic on the GPU" as *emitting
  particles*: a parent emitter's particles carry a spawn accumulator and emit, through the E9 event path, into a child
  emitter each frame (§2.9). That gives particles as emitters at any count with no new pass and no new state table.
  Alternative: a GPU emitter table with its own pass, as first planned.
- **Reviewer note on E8.** Particles after blended materials draw *over* glass that is in front of them, untinted,
  and transmissive glass never shows them (its `SceneBehind` copy is taken before particles). Opting an emitter into
  WBOIT fixes the first only. The review proposes a per-emitter **glass order** (§2.5): after (the default), before
  (drawn before the blended materials, so glass tints and refracts them), or split per pixel by the nearest glass depth.
  Alternative: keep E8 as it is and accept the artefact near windows.
- **Reviewer note on E15.** Mesh particles as `GpuScene` instances are the right idea, but the instances must be
  written before culling, which takes their emitters off the async queue, and they are not stable per particle (the
  survivor copy moves particles between ids), and the top level skips rebuilds by `GpuScene.version()`, which GPU
  writes do not change. §3 lists the changes: a GPU-written id range left out of ray tracing (mask 0, never traced), an
  *early group* of emitters simulated on the graphics queue before culling, and previous transforms written per slot by
  the particle pass. Alternative: mesh particles drawn by their own instanced draw from the pool's meshes (P15 option
  2), which keeps the simulation off the culling's critical path but duplicates culling, LOD and shadows.
- **Reviewer note on E18.** Keys in the shader are exact, and cheap when curves have few keys and are cached per chunk
  (§2.7). Gradients with many keys and many curves per particle cost dependent loads in a loop for every particle.
  Recommendation: keep E18 with a cap of 32 keys per curve and the caching below; if step 4's benchmark shows curves
  above 15 % of the update, bake curves of more than 8 keys into rows of a buffer (an atlas for long curves only).

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
   gameplay inputs; per-particle work is what scales. (E2 keeps the CPU runtime as the default and adds emitter state
   on the GPU for particles as emitters.)
2. **One simulator per behaviour.** Niagara's and Shuriken's CPU/GPU split costs twice. VFX Graph is GPU only;
   Destiny and PopcornFX run one description through one compiler. The engine's §4.19 already says: *no* switching one
   system between CPU and GPU. (E1 chose CPU and GPU emitters anyway; §2.9 keeps the cost down: a fixed target per
   emitter, a declared CPU module subset, and the Java simulator as the GPU's test reference.)
3. **GPU sub-emitters through events whose payload is the parent's attributes** (VFX Graph, bevy_hanabi), consumed by
   a spawn pass. Niagara's CPU-only events are the cautionary tale.
4. **Batch gameplay-driven bursts into one shared instance** (Niagara Data Channels), so a hundred impacts are not a
   hundred systems. Niagara spawns one listener system per *island*, a bounded region, not one for the whole world.
5. **Scalability is assets shared by many effects** (Niagara Effect Types): distance, caps, significance, and spawn
   scaling by quality.
6. **Compiling one shader set per effect hurts iteration** (Niagara, VFX Graph). Destiny's bytecode and Niagara's
   lightweight emitters both move away from it.
7. **Lit smoke is six-way lightmaps today. Unified volumetrics** (Frostbite) is the high end.
8. **Colliders and attractors are scene objects found by layer** (Godot), plus depth, SDF or ray tracing for "the
   world".

---

## 2. Architecture for true-sim (as decided)

### 2.1 Principles (E1 to E22; the options weighed are P1 to P22 in section 4)

| | Principle | Why |
|---|---|---|
| E1, E2 | **Each emitter has one fixed target**, GPU (default) or CPU, set in the asset; emitter logic runs in the CPU runtime, or on the GPU as emitting particles. Nothing switches an emitter between targets at runtime or as a fallback (§4.19's "don't" stands). | Gameplay reads CPU particles; the bulk scales on the GPU; no state migration across a readback. |
| E3, E4 | **Effects are data, interpreted by one kernel** (a few capability variants), from module records by name. | Effects load with nothing to compile; editors and files work on data. |
| E5, E6 | **Ranges reserved per instance, survivors copied in order.** | No spawn fails mid-effect; order is deterministic, which ribbons and stable LOD subsets need. |
| E7, E8, E19 | **Blended particles draw sorted per view (per pair of eyes), premultiplied, after blended materials,** with per-emitter WBOIT and glass order. | Correct additive and alpha; no stereo rivalry; half the sorting in VR. |
| E9, E22 | **Sub-emitters and bursts through GPU events and spawn records,** sorted before they spawn; bursts shared per asset and cell, or instances of their own, per call. | GPU-native, deterministic, and a thousand impacts are a few draws. |
| E10, E14 | **Collision and lighting chosen per emitter,** from fixed engine methods, with fallbacks where a device lacks one. | Each effect pays for what it needs; §4.18 keeps render methods fixed. |
| E11, E12 | **Deterministic inputs and seeds; once per frame, clamped; substeps on request.** | Replays and clients look alike; testable. |
| E15, E16, E17 | **Mesh particles are `GpuScene` instances; CPU bounds for LOD, GPU bounds for drawing; float offsets from a high-precision origin, world or local space.** | Shadows, materials and culling come with instances; nothing waits for a readback; precise anywhere. |
| E18 | **Curves are keys, searched in the shader,** cached per chunk. | Exact; no atlas to manage. |
| E20, E21 | **Effect types carry far and off-screen behaviour; the scene side is `scene-effects`.** | Shared scalability; servers take it without render data. |

### 2.2 Where things live

**scene, new module `scene-effects`.** Its dependencies are core, assets, common-math and common-jobs. It has nothing
of rendering, so a server replicates which effects play and runs CPU emitters gameplay depends on.
- `EffectData`, an asset (`EffectAssets.EFFECT`): emitters, user parameters with defaults, and an `EffectType` (its
  budget class). Equal data is one asset (`data.ref()`), as for `MaterialData`.
- `EmitterData` has:
  - its **target**: `Target.GPU` (the default) or `Target.CPU` (E1);
  - timing: delay, duration, loops, loop delay, prewarm;
  - its *spawn* (rates, bursts, per distance, from events) and capacity;
  - *initializers* (at birth) and *updaters* (each frame), as module records with parameters;
  - *events* it sends (birth, death, collision, periodic, emit-per-frame for particles as emitters) and which emitter
    they spawn;
  - *renderers* (billboard, mesh, ribbon, trail, beam; later light and decal), each with its lighting (E14), blend,
    glass order and sort mode;
  - space (world or local, E17) and its collision choice (E10).
- Module records are generated from Slang structs (`[ParticleModule]`), as material parameters are. Scene keeps them
  *by name and field*, like `MaterialData`, so scene still knows no engine types. Each module type declares its
  **targets** (GPU, CPU or both) and its **capability** (none, ray queries). The engine resolves names against its
  registry; building an effect checks every module against its emitter's target. An unknown module is an error with
  "did you mean…", and so is a GPU-only module on a CPU emitter.
- Values: `FloatCurve` and `ColorGradient` (keys: time, value, and step, linear or cubic per key; at most 32 keys),
  `Range` (uniform random between two values), and `Binding`. A binding is a module input taken from a constant, a
  user parameter, a curve over normalized age, a curve of a user parameter, a curve of speed, or a random range.
- `Effects`, the per-scene store of playing effects (a component on nodes, §4.20 style), with a change log on the
  scene's clock. For each playing effect it holds:
  - the effect asset, node (or joint node, or world pose) and seed;
  - start tick (world clock), state (playing, stopping, stopped), time scale and paused flag;
  - user parameter values, the attachment (node, local offset, inherit velocity) and, for shared bursts, the cell.
- `EffectRuntime`: emitter logic on the CPU. Per instance and emitter it advances timing and computes this frame's spawn
  counts, with sub-frame birth times and positions along the emitter's motion. It decides LOD and culling by
  significance. It is deterministic (integer ticks, §2.10) and allocation-free per frame, like `Animators`.
- `CpuSimulation`: the CPU emitters' particles (E1), simulated on the job system's threads by the Java implementations
  of the CPU-capable modules, in the same order and with the same survivor copy as the GPU kernel. Gameplay reads them
  (`effect.particles(emitter)`: count, positions, velocities, a cursor allocating nothing). It is also the reference the
  GPU tests compare against.
- `ParticleWorld`: what CPU emitters collide with, an interface the game supplies, like animation's `Ground`
  (`raycast(...)`, `height(x, z)`), thread-safe and allocation-free; the game implements it over common-collision's
  `CollisionQuery` (one per worker thread) or `TerrainHeights`. So `scene-effects` does not depend on common-collision.
- `EffectType`: maximum distance, instance caps, significance (distance or age), spawn scale per quality level, what
  happens when culled (kill, pause, keep simulating), a priority, LOD distances with hysteresis, and the cell size of
  shared bursts.

**engine-render (`com.truesim.engine.render.particles`):**
- `ParticlePool`: the shared particle buffers (two copies, device-local), sized by a budget in bytes. It allocates
  ranges in chunks of 64 slots per emitter instance (`RangeAllocator`, deterministic first fit). It can grow like
  `GeometryPool` (doubled, copied on the GPU next frame, the old buffer retired through frame slots), but a copy of a
  large pool is a visible hitch, so the budget is set for the content and growth is reported.
- `[HostLayout] struct Particle` in `particles/particle.slang` (named so in step 4: see §8). The Java layout is generated (`slang.javaPackage`), so
  Java and Slang share one source (pitfalls: "a layout computed twice").
- `EmitterTable`: a record per emitter instance (`RecordBuffer`, a few change per frame): its range, module program
  offset, its effect instance's origin (two floats per axis), transform and previous transform, time scale (0 paused),
  user parameter block, renderer settings, LOD fraction, bounds slot and group (early or main, §2.4).
- `ModuleProgram`: the encoded module lists. Each emitter's modules are encoded once per `EffectData` into a word
  stream (opcode, parameter offsets), shared by every instance of the effect, together with the effect's curve keys.
- `CurveKeys`: every curve's and gradient's keys in one buffer, a block per effect (E18), allocated and freed with
  effects like material table blocks; the kernel caches an emitter's block per chunk (§2.7).
- `SpawnRing`: the per-frame spawn records the CPU writes (emitter instance, count, first spawn index, birth-time
  spread, start and end transforms, an inherited velocity, a burst payload of four floats and an int). This is §4.19's
  spawn-record ring, in a `FrameBuffer`.
- `CpuEmitters`: the CPU emitters' particle records, written each frame by `CpuSimulation` through the generated
  writer into a per-frame mapped buffer and copied into their ranges of the pool, so every renderer, the sort and the
  culling treat them like GPU particles. §4.19's game-written records are one case of it: a CPU emitter whose updater is
  the game's code (`CpuEmitters.writer(effect, emitter)`).
- Techniques and requests:
  - `ParticleSimulation`, a frame technique (once per frame, every view sharing it): the early group on the graphics
    queue before culling, the main group on the async compute queue (§2.4);
  - `ParticleSort`, a shared request per view or per pair of eyes (equal requests from both eyes' draws are one sort,
    pitfalls: "one technique instance in two views");
  - `ParticleDraw`, per view: culling, billboards, ribbons, trails and beams, in sorted order;
  - mesh particles need no draw: they are `GpuScene` instances.
- Keys: `ParticleKeys.PARTICLES` (the simulated pool) and `ParticleKeys.EVENTS`, buffers, which plugins `modify` for
  custom forces and collision (§4.18).

**engine-scene:** `EffectSync`, like `SceneSync`. It follows `Effects`' change log. It acquires `EffectData` (and its
textures and meshes) through `SceneResources` and encodes the module programs. Each frame it runs the
`EffectRuntime`'s spawn step and `CpuSimulation`, and writes emitter records (transforms of attached nodes from the
scene graph in doubles, interpolated as drawn, §2.4; joints are nodes) and spawn records.

**engine-app:** an `Effects` plugin, giving a world's effects with the frame graph wired: the simulation in the frame,
a `ParticleDraw` per view (`addPerView`), the quality setting `effects.quality`, and `EffectBindings` (animation events
and curves to effects, §2.6), which needs both `scene-effects` and `scene-animation`.

**scene-animation:** nothing is required. An `Animator.node(Joint)` overload (today `node(joint.index())`) would read
better in effect code. `scene-effects` does not depend on `scene-animation`.

### 2.3 Data on the GPU

```slang
[HostLayout]
public struct Particle            // 80 bytes; offsets from the instance's origin (world) or in its frame (local)
{
    public float3 position;  public float age;        // seconds since birth
    public float3 velocity;  public float lifetime;   // age >= lifetime: dead (or the DEAD flag)
    public float4 color;                              // linear, premultiplied in the renderer
    public float2 size;      public float rotation;   public uint seed;   // hash of (effect seed, emitter, spawn index)
    public uint flags;       public uint spawnIndex;  public float2 custom;  // flipbook frame, ribbon id, user data
};
```

- **Positions** are float offsets from an origin per *effect instance* (two floats per axis, like instances), shared
  by its emitters, so world-space parents and children need no conversion. The draw subtracts the camera as instances
  do, so effects are precise 7,000 km out.
  - **World-space emitters** keep the origin fixed at spawn. The CPU *rebases* it when the instance has moved more than
    a few kilometres (a car's exhaust on a long drive): a tiny pass subtracts the shift from all the instance's ranges,
    in the early group, before anything reads them.
  - **Local-space emitters** store positions in the emitter's frame (its node or joint, with its offset). Collision,
    sorting and drawing transform them by the emitter record's matrix; a world-space child spawned from a local-space
    parent takes the parent's position through that matrix at its birth.
  - **Shared bursts** (E22) have an origin per cell (§2.7).
- **Extra attributes** for emitters that need them (normal, angular velocity, mesh particle orientation, previous
  position, a GPU emitter's accumulator, trail history) live in side arrays laid out per emitter range and copied with
  the survivors, so the common particle stays 80 bytes.
- **Random numbers:** `rand(seed, salt)` is a PCG hash on 32-bit integers, and a float is its top 24 bits times 2⁻²⁴,
  so Java and Slang draw the same values bit for bit. The seed is fixed per particle at birth, and each module has its
  own salt. Values do not depend on the slot, the thread count, the order of atomics, or the order of modules.

### 2.4 A frame

`FIXED` → `UPDATE` (the game plays, stops, moves and sets parameters) → `ANIMATE` (animation events fire) → `LATE` →
scene update and **effect sync** → render (D124 with animation's `ANIMATE`).

**Effect sync, on the CPU, allocation-free:**
1. **Follow the change log.** Effects added or removed claim or free ranges, emitter records and module programs.
   A failed reservation applies the effect type's policy: lower LOD, steal the least significant instance, or skip.
2. **Advance each playing instance by the world clock's delta times its time scale.** A paused world (D128) gives a
   delta of 0: nothing spawns or moves, every view still draws (a photo mode can fly around frozen smoke).
3. **Timing:** delay, duration and loops. Then **significance:** distance to the nearest camera and age, against the
   effect type's caps and budgets. This decides each instance's LOD (a spawn scale, an update rate, a drawn fraction)
   or culls it, with hysteresis (§2.7, scalability).
4. **Spawn counts per emitter instance:**
   - rate × dt plus a carried fraction (fixed point, §2.10);
   - bursts at their times, across loop ends;
   - per unit of distance travelled;
   - and the *birth times* within the frame.
   Each count becomes one spawn record carrying the previous and current transforms, so the spawn pass places
   particles along the motion (no gaps behind fast emitters).
5. **Transforms as drawn.** Nodes moved in `FIXED` are drawn interpolated between their last two steps (D91, on the
   GPU). The sync keeps each attached node's previous step transform and takes the emitter transform at the frame's
   blend factor, so a car's exhaust leaves the drawn car, not the simulated one a step ahead. Until D91 is built, the
   world transform as it is.
6. **CPU emitters** (E1) simulate on the job system: each emitter alone, results independent of the thread count.
7. **Write the frame's records.** Emitter records, CPU emitters' particles, spawn records and bursts from
   `effects.burst` go into their buffers.

**On the GPU, once a frame.** Emitters are in one of two **groups**, chosen by the CPU from their renderers and inputs:
- the **early group**, on the graphics queue after the scene upload and the deformation pass and before culling:
  emitters with mesh renderers (their instances must be written before culling reads them) and rebases;
- the **main group**, on the async compute queue where the GPU has one, overlapping shadow maps and opaque drawing,
  finished before `Anchor.TRANSPARENT`: everything else.

Both groups run the same passes over their own chunk lists:

| Pass | What | Dispatch |
|---|---|---|
| `particle upload` | Emitter record scatter (`RecordBuffer`), the spawn ring, CPU emitters' records copied into their ranges | frame start |
| `particle count` | Per chunk: survivors (`age + dt < lifetime`, not flagged dead), by wave ops | one group per chunk of the group's list |
| `particle scan` | Per emitter: exclusive scan of chunk survivors; for shared bursts, how many oldest survivors to drop for the CPU's spawn demand | one group per 1,024 chunks |
| `particle update` | Reads copy A. Caches the emitter's curve keys in group-shared memory, runs its updaters (forces, noise, collision, attributes over life) and integrates. Writes survivors to copy B at their scanned slot (order kept). Appends events (target, parent slot in B, salt). Accumulates bounds (atomic min/max on ordered ints: order-independent). Writes trail history. | indirect |
| `particle spawn args` | Sorts the events by (target emitter, parent slot), a radix sort over the event count (usually thousands, capped); per emitter, spawn count = records + events clamped to room (overflow counted); the spawn dispatch arguments | one small dispatch, then indirect |
| `particle spawn` | One thread per new particle. Sources are the spawn records, then the sorted events (parents read from copy B, just written). Runs the initializers, places the particle at its birth time along the emitter's motion, and integrates its remaining part of the frame (or its prewarm age, §2.7). | indirect |
| `particle args` | Per emitter instance: alive count, draw and dispatch arguments, LOD fraction. Mesh particles (early group) write their `Instance` records and previous transforms into the reserved `GpuScene` range. | one thread per emitter instance |

- **Deaths** decided in `update` (collision kill, kill volumes) set the `DEAD` flag and leave at the next `count`. A
  particle killed by collision lives one more frame, invisibly (its alpha is set to 0).
- **Events** appended by `update` spawn in the same frame's `spawn` pass. Birth events of particles born in `spawn` wait
  for the next frame. So a burst on death of a burst on death takes one frame per level of birth events.
- **Sorted events keep E5 and E11.** Atomic appends come in a different order from run to run; children spawned in
  that order would take different slots, so unsorted emitters, ribbons of children and the stable LOD subsets would
  differ between runs on one GPU. The sort by (target, parent slot) makes the children's order a function of the
  inputs.
- **Statistics** (alive counts, overflows, events dropped) come back through `g.readback`, a few frames late. They are
  for budgets and debugging only, never for gameplay (§4.19).

**Async compute, what to watch for:**
- The graph orders by whole buffers, not ranges: a pass writing part of the instance buffer is ordered after every
  writer of it (the scene upload) and before every reader (culling, the top level). That is why mesh-particle emitters
  are in the early group instead of making the whole simulation wait.
- Skinned-mesh emission reads the pool's positions, which the deformation pass writes in place, without a history:
  declared reads order it after deformation (early in the frame). Never read them on the async queue undeclared.
- Ray-query collision reads this frame's top level: its variant (§2.8) is ordered after the `TopLevel` build.
- Depth collision reads last frame's depth (a history), so it waits for nothing this frame.
- The draws read the simulation's arguments; with the main group on the async queue, the first `ParticleDraw` waits
  on its timeline semaphore. That wait is the cost to measure: if the main group is longer than the opaque work, it
  shows up on the frame.
- On lavapipe (one queue family) async passes run on the graphics queue; images and buffers list the family once
  (pitfalls). Tests compare the same frames with async on and off.

**Per view** (each `ParticleDraw`; a VR pair shares the work in brackets):

| Pass | What |
|---|---|
| `particle cull` | Emitter instances against the view's frustum, by their GPU bounds; distance LOD (a drawn fraction, a stable subset by `spawnIndex` hash, faded at its edge; ribbons by stride, §2.5). Lists the visible draws per pipeline. |
| `particle sort` | [Per pair, `ParticleSort` request] Keys are depth along the pair's center ray, for sorted emitters only. A radix sort, indirect (FidelityFX-style 4 bits a pass); 16-bit keys where an emitter is small. |
| `particle draw` | At `Anchor.TRANSPARENT`, after `Transparency.ID` (or before it, for emitters drawn before glass, §2.5), over `SCENE_COLOR`, testing `SCENE_DEPTH`. Emitters as wholes, back to front by their CPU bounds' distance; within an emitter in sorted order, premultiplied (`ONE, ONE_MINUS_SRC_ALPHA`). Soft fade reads depth. Lit emitters read `LIGHT_LIST` and the shadow inputs. Optional: into a half-resolution target, then a depth-aware upsample over the scene. |

Mesh particles need no pass of their own. As `GpuScene` instances they are culled, drawn, shadowed and shaded by the
existing techniques. Their ids are slots that the survivor copy reuses for other particles, so occlusion history only
loses efficiency for them (the two phases stay conservative), and previous transforms come from the particle pass per
slot, not from a buffer kept by id.

**Draw count.** Emitters drawn as wholes in order cost a draw each (an indirect draw with its GPU count). Unsorted
emitters of one pipeline (additive sparks) merge into one indirect multi-draw. A view with 2,000 sorted emitter
instances records 2,000 draws per eye; shared bursts and LOD keep that down, and recording runs on the graph's worker
threads.

### 2.5 Rendering paths

- **Billboards:**
  - facing the camera position (the pair's center in VR) with world up;
  - facing the view plane (desktop only: in VR it rolls with the head, so it falls back to facing the position);
  - velocity-aligned with stretch by speed (sparks, rain);
  - axis-locked (cylindrical: fire, grass-like);
  - free (oriented by a particle normal: shockwave rings on the ground).
  Four vertices from `SV_VertexID`, no vertex input (as the engine's draws). Sub-rectangle trimming (fitted quads or
  polygons per flipbook frame) cuts empty overdraw.
- **Flipbooks:** a frame from age, speed or a parameter. Two frames blended, optionally with motion vectors from a
  second texture. Textures are bindless slots of `Textures`, as in materials.
- **Soft particles:** fade by the depth difference to the opaque scene, in metres, read per eye from the eye's own
  depth (both eyes fade alike: the difference is in view depth). A **volume fade** option takes the path length of the
  view ray through the particle's sphere against the scene depth: it reads as a puff in stereo, where flat cards show
  their intersections. Near-camera fade uses the distance to the head (the pair's center), so both eyes fade together.
- **Blending:** premultiplied alpha only. Additive is premultiplied with alpha 0, and "alpha to add" blends between
  them (glowing embers that thin out), with one pipeline. Opaque and masked billboards (leaves, debris) draw
  separately later, as a `GeometryProvider` through `SceneDraw` (then they write depth, cast shadows and get motion
  vectors).
- **Glass order** (per emitter; see the reviewer note on E8). Blended materials draw through WBOIT, which writes no
  depth, and transmissive ones sample `SceneBehind`, a copy of the opaque scene taken when `Transparency` builds.
  - *After glass* (the default): drawn after `Transparency.ID`. Right for effects in front of glass; behind a window
    they draw over it, untinted.
  - *Before glass*: drawn at `TRANSPARENT` *before* `Transparency.ID`, so `SceneBehind` holds them and glass tints and
    refracts them. Right for a fire seen through a window; wrong where the effect is in front of glass.
  - *Split* (proposed, open question 1): the transparency technique also writes the nearest blended depth; the emitter
    draws twice, its fragments behind that depth before glass and the rest after. Right for one layer of glass, at the
    cost of a second draw of the emitter's vertices.
  - *Order-independent* (E8's opt-in): into WBOIT's targets through the `GeometryProvider.drawBlended` hook that
    already exists; no sort, wrong for additive.
- **Lit** (E14, per renderer):
  - *unlit* (emissive color only, the default);
  - *lit per vertex*: the light list's lights evaluated at the particle's center (its cluster looked up from the
    projected center), plus sun shadow, sun and sky;
  - *lit per pixel* with a normal map (a sphere normal for round puffs);
  - *six-way* lightmaps for smoke (map A: right, top, back and alpha; map B: left, bottom, front and emission).
  All read `lights.slang`'s `ILightList`, so every light binning works. The shading model is the engine's (§4.18:
  render methods stay fixed). A sun-only fallback applies without a light list. Effect types may lower the lighting by
  quality (`perPixel()` drawn `perVertex()` below high).
- **Trails** keep a history per particle: N past positions in a ring per particle (N = 4 to 32), drawn as a
  camera-facing strip. They fit sparks and tracer rounds. The survivor copy moves the history with the particle: 12 × N
  bytes per particle per frame, about 4 MB a frame for 10,000 particles of 32 points (negligible on the RX 9070 XT,
  not for a million), so trail emitters have their own count cap.
- **Ribbons** connect *the particles of one emitter instance in spawn order*. The survivor copy keeps that order over
  the whole range, across chunk boundaries (the scan is per emitter), so particle i and i+1 are neighbours in the
  buffer, and the vertex shader builds the strip from them.
  - **The head** is the emitter's current position (from the emitter record), not the last particle born, so a sword
    trail reaches the blade between spawns and there is no first-frame gap.
  - **Several ribbons** per emitter use a ribbon id (`custom`): particles of one id are not adjacent when ribbons
    interleave, so such emitters get a second sort key (ribbon id, spawn index), sorted only for them. Ribbons from
    particles as emitters (a trail per spark) take the parent's spawn index as their id (§2.9).
  - **Gaps:** a particle dying before its neighbours (random lifetimes) joins its neighbours directly; emitters that
    need clean ribbons give one lifetime per ribbon.
  - **LOD** draws every k-th particle by spawn index (a stride), never a hashed subset, which would tear the strip.
  - **Space:** world space by default (a trail stays where the blade was); local space for a ribbon that moves with its
    emitter (a ring around a rotating orb).
  - **Sorting:** a ribbon emitter sorts as a whole; its own segments draw in spawn order (additive ribbons don't care).
- **Beams:** analytic segments between two points (an emitter's two user parameters or two nodes), subdivided with
  noise. No simulation.
- **Mesh particles:** `GpuScene` instances that the GPU writes (E15; §3 says what `GpuScene` and ray tracing need):
  mesh, material, orientation (from velocity, random, or angular velocity) and scale. They cast shadows when the
  emitter says so (off by default: debris is small and shadow views cost). They are never traced: their records enter
  the top level with mask 0. Tint and alpha per particle need the per-instance custom data that README promises
  ("custom data per instance will be buffers by id"). Until then they use the material's color.
- **Distortion** (heat haze, shockwaves) draws offsets into a half-resolution buffer and a pass offsets `SCENE_COLOR`
  reads. It comes later; it cannot reuse `SceneBehind` (opaque only, so glass would vanish behind the haze), it reads a
  copy taken after transparency.
- **Lights from particles** (step 17b): a light module per emitter (every particle, a fraction or the brightest N;
  radius; intensity times the particle's color and alpha, so a fading spark dims its light) appends point lights to a
  GPU-written light range in `Lights` (count from the GPU) that `ClusteredLights` bins; today it takes
  `lights.count()` from the CPU. A budget per emitter and a global one keep the brightest when full. They cast no
  shadows (a shadowed light on the effect's node serves a torch), and everything the clustered lights reach sees them:
  both shading techniques, every view, lit particles. **Decals** come with a decal system.
- **Volumetric look:** six-way smoke now. Voxelizing particles into froxels (Frostbite) when the engine gets volumetric
  fog.

**Views.**
- Every graph view gets a `ParticleDraw` (`addPerView`): the camera, both eyes, a spectator, a mirror, a security
  camera's texture. A view that should not show particles drops it (`port.remove(ParticleDraw.ID)`); a small view sets
  a particle LOD bias. Views skipped this frame (`view.enabled`, `view.rate`) cost nothing.
- **Shadow views** draw no billboards, ribbons or trails: blended surfaces cast no shadows yet (application-api
  D136b), and particles follow. Mesh particles cast as instances. Shadows from smoke (opacity or deep shadow maps) wait
  for a shadow design.
- **Motion vectors** come with TAA (frame-graph-v2 §6's `MOTION` key; animation A6). Blended particles write none
  (they write no depth either); they write a *reactive mask* where they cover, so temporal antialiasing and upscalers
  trust history less there (as FSR's reactive mask and Unreal's responsive AA do). Mesh particles take previous
  transforms from the particle pass (their previous position and orientation, per slot). Opaque billboards (later)
  write position minus velocity times the frame's dt.

**Overdraw** is the cost that matters most on a headset at 90 Hz (numbers in §2.11):
- each emitter has a maximum on-screen size fraction (big near-camera sprites fade or shrink);
- a half-resolution target per emitter (a render flag) with the mixed-resolution upsample, per eye;
- distance LOD draws a fraction of the particles, with sizes grown to keep coverage;
- `DynamicResolution` already scales the whole view; variable rate shading (frame-graph-v2 §6) can later shade smoke
  at 2 × 2.

### 2.6 Authoring and the game API

**Effects are data, built in code or loaded later from files.** A serialization format comes in its own repository,
like the scene's.

```java
// Typed constants, declared once.
static final EffectParameter.Float INTENSITY = EffectParameter.ofFloat("intensity");
static final EffectParameter.Color TINT = EffectParameter.ofColor("tint");
static final EffectType IMPACTS = EffectType.builder("impacts")
    .maxDistance(80).maxInstances(64).significance(Significance.AGE).whenCulled(Culled.KILL)
    .burstCells(64).build();                                    // shared bursts per 64 m cell

// An effect: emitters, each a target, a spawn, initializers, updaters, events and renderers.
EffectData sparks = EffectData.builder(IMPACTS)
    .parameter(INTENSITY, 1f).parameter(TINT, Color.rgb(1f, 0.6f, 0.2f))
    .emitter("sparks", e -> e
        .capacity(400).space(Space.WORLD).duration(0.2).loops(1)
        .spawn(Spawn.burst(0, Bind.param(INTENSITY).times(150)))
        .init(Shape.cone(Degrees.of(30)).radius(0.02),
              Init.lifetime(Range.of(0.3, 0.8)), Init.speed(Range.of(4, 11)),
              Init.size(Range.of(0.01, 0.02)), Init.color(Bind.param(TINT)))
        .update(Forces.gravity(), Forces.drag(0.6f),
                Collide.rays().orElse(Collide.depth()).bounce(0.35f).friction(0.2f)   // E10, with a fallback
                    .onCollide(Event.spawn("embers", 1).chance(0.3f)),
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

// A CPU emitter (E1): gameplay reads its particles; it collides with the game's ParticleWorld.
EffectData grenadeShards = EffectData.builder(IMPACTS)
    .emitter("shards", e -> e.target(Target.CPU).capacity(24)
        .spawn(Spawn.burst(0, 24)).init(Shape.sphere(0.1), Init.speed(Range.of(15, 25)))
        .update(Forces.gravity(), Collide.world().kill())
        .render(Mesh.of(SHARD_MESH, SHARD_MATERIAL)))
    .build();

// Playing effects: handles made once and reused; per-frame calls take primitives and allocate nothing.
Effects effects = new Effects(scene, world);                    // world: a ParticleWorld, for CPU emitters
Effect torch = effects.play(TORCH_FIRE, torchNode);             // attached: follows the node
torch.set(INTENSITY, 0.8f);                                     // a user parameter, any frame
torch.timeScale(0.5f);                                          // slow motion; effects.timeScale(s) for all
torch.stop();                                                   // stops spawning; particles live out their lives
torch.kill();                                                   // gone now

Effect trail = effects.play(SWORD_TRAIL, a.node(handR.index())) // joints are scene nodes: sockets
    .offset(0, 0.4, 0).inheritVelocity(1f);

Effect shot = effects.spawn(MUZZLE_FLASH, muzzleNode);          // an instance of its own (E22): movable, stoppable
effects.burst(BULLET_IMPACTS, x, y, z, nx, ny, nz, surface);    // into the shared instance of its cell (E22)

Effect shards = effects.spawn(GRENADE_SHARDS, x, y, z);
ParticleCursor c = shards.particles("shards");                  // CPU emitters only: read by gameplay
while (c.next()) damage(c.x(), c.y(), c.z());

// Animation drives effects (engine-app's EffectBindings; by hand it is animators.listen and a.curve).
EffectBindings fx = new EffectBindings(effects, animators);
fx.onEvent(LEFT_FOOT_DOWN, DUST, footL).scale(INTENSITY);       // played at the joint, the event's weight as intensity
fx.onEvent(RIGHT_FOOT_DOWN, DUST, footR).scale(INTENSITY);
fx.curve(a, BLINK, torch, INTENSITY);                           // an animation curve drives a parameter every frame

// Engine side, through the plugin or by hand:
EffectSync sync = new EffectSync(resources, effects, particles);  // follows Effects, like SceneSync
graph.add(new ParticleSimulation(particles));                     // the frame's simulation
graph.addPerView(port -> new ParticleDraw(particles, sceneViews.of(port)));
```

**The API's rules,** in the style of animation's:
- Setup objects (`EffectData`, `EffectType`, parameters, bindings) are immutable; the handles an instance changes are
  made once: calls take primitives, look nothing up by name and allocate nothing.
- **Bursts are a choice per call** (E22): `effects.burst(...)` goes into the shared instance of the asset and the
  cell (a payload of position, normal and one int or four floats per burst, read by the asset's `Bind.burst(...)`
  inputs); `effects.spawn(...)` and `effects.play(...)` make an instance of their own and return its `Effect`.
- **Handles of ended effects are inert:** an effect ends on its own (duration plus the longest lifetime, known on the
  CPU without a readback), so `stop()`, `set(...)` and `kill()` on an ended effect do nothing and `alive()` says so,
  unlike a removed `Node`, which throws. The generation in the handle keeps it from touching a reused slot.
- **Animation events carry no joint**, and `Animators.Listener` gets the animator's first node, so the binding names
  the joint (`fx.onEvent(event, effect, joint)`); one event per foot (`LEFT_FOOT_DOWN`) is clearer than one footstep
  event and a lookup. The surface under the foot (`TerrainHeights.materialAt`) can pick the effect in a listener.
- **Parameters from gameplay:** `effect.set(...)` any frame, or a binding evaluated by `EffectSync` (an animator's
  curve, a node's speed, a `FloatSupplier` made once).
- **Animation level of detail** (`Animator.updateEvery(n)`) fires events on the animator's updates only; effects
  attached to its joints still follow the joints every frame.

**Gameplay-relevant particles** (§4.19 and E1): a CPU emitter, simulated by `CpuSimulation` and read through
`ParticleCursor`; or the game's own simulation writing records through `CpuEmitters.writer(effect, emitter)`
(`position(x, y, z).color(...).size(...)`). Both are drawn exactly like GPU particles. GPU particles are never read
back.

**Composition:**
- An effect is many emitters with shared user parameters.
- Each emitter has a delay, a duration, loops and a loop delay. These give timing offsets and sequences (a flash at
  0 s, debris at 0.05 s, smoke from 0.1 s for 3 s).
- Templates: `EmitterData.from(template).with(...)` overrides inherited settings, like Niagara's inheritance but on
  values.
- Per-instance overrides are user parameters only. Changing structure means another asset.

### 2.7 How it works

- **Emission shapes**, as initializers that set position (and optionally velocity and normal):
  - point; sphere and shell (radius, thickness, arc); box and box surface; cone (angle, radius, length, from base or
    volume); circle, disc and ring; line, edge and polyline; torus;
  - **mesh surface, vertices or volume** of a static mesh, from the `GeometryPool`'s data by the instance's mesh number:
    an alias table over the triangle areas of the mesh's finest level (the pool holds every level's meshlets; the
    table lists level 0's triangles as meshlet and local triangle), built once per mesh when an effect first uses it;
    positions are dequantized with the mesh's box;
  - **skinned mesh surface** reading the *deformed copy* the deformation pass writes (A1 of animation), ordered after
    it (§2.4). The alias table is the rest pose's (areas change little), positions and normals are the posed copy's,
    dequantized with the copy's posed box. A copy is only re-skinned when its pose changed, so a still character
    emits from its last pose, which is right. GPU emitters only: the CPU never sees the posed vertices;
  - **texture or density map** projected on a shape (a burning logo, a mask), by an alias table over texels;
  - **SDF and volume** (rejection sampling in a 3D texture), later with SDFs.
- **Sockets and bones:** an emitter attaches to a node, so a joint (`a.node(joint.index())`), a child node at a muzzle,
  or a model's named node serve as sockets; an offset places it. Local-space emitters move with it; world-space
  emitters spawn along its motion. **Emission from bones** (along a joint chain, a fire running up an arm) is a shape:
  segments between the joints' world positions, written into the emitter record each frame (up to 16 joints).
- **Updaters**, in the order listed:
  - forces: gravity, drag (linear and quadratic), wind (a world wind parameter plus gusts), vortex, point attractor and
    repeller (falloff, kill radius), line attractor, orbit (angular velocity around an axis), curl noise (3D, animated,
    octaves), vector field (a 3D texture in a box, tiled or clamped), and attractor shapes from the scene (below);
  - limits: speed limit, force limit, speed by curve;
  - attributes over life, by speed or by parameter: size, color and alpha, rotation (and rotation speed), flipbook
    frame, light intensity;
  - **emit** (particles as emitters, E2): a rate, a per-distance count or bursts at ages, into a child emitter (§2.9);
  - **neighbour grids** for flocking and separation, later: a grid simulation stage as Niagara does it (clear, insert,
    query), when someone needs it.
- **Curves and gradients** (E18) are keys searched per particle. To keep that cheap:
  - a chunk holds one emitter, so the update caches the emitter's key block (at most a few hundred bytes) in
    group-shared memory once per chunk and searches there, not in device memory;
  - curves of two keys fold to a lerp and constant curves to a value when the program is encoded;
  - up to 4 keys are searched linearly, more by binary search; evenly spaced keys (flagged at encode) index directly;
  - at most 32 keys per curve, checked when the effect is built;
  - step 4 measures 8 curves of 8 keys at a million particles; the reviewer note on E18 says what follows if it costs.
- **Colliders and attractors** are scene objects too: `EffectColliders` on nodes (plane, sphere, box, capsule), each
  an `EffectCollider` component of its node (D46, since 2026-11-04: one per node, saved, gone with the node).
  A node's own effect (a torch's flame) is an `EffectEmitter` component, started by `Effects.advance` while it is
  there (`effects.emitted(node)`).
  Effects use them by layer mask (Godot). Collision response is bounce (restitution, friction), stick (attach to the
  surface), slide, or kill. Each can send a collision event. Per emitter (E10), a **chain** of methods, the first the
  device and target support being used (`Collide.rays().orElse(Collide.depth())`):
  - **Analytic colliders** on nodes: exact, cheap, GPU and CPU.
  - **Height field:** a texture filled from `TerrainHeights` in tiles around the camera as they load (the CPU samples
    `heightAt` on a grid and uploads changed tiles). It is right off screen, outdoors. GPU and CPU.
  - **Depth-buffer collision** uses one view's previous-frame depth, kept as a history (`g.history`) and reprojected
    with that view's previous view-projection and render region (dynamic resolution moves it). The view is named on
    `ParticleSimulation`: the main camera, the left eye in VR. It is cheap and wrong off screen and behind occluders.
    GPU only.
  - **Ray queries** against the frame's top level (`RayTracing`, inline `rayQuery`), where `VulkanDevice.rayTracing()`:
    exact, off screen too, one segment per particle per frame. Its own kernel variant (§2.8), ordered after the top
    level's build. Mesh particles are not in the top level, so particles never hit each other. GPU only.
  - **The physics scene** through `ParticleWorld` (raycasts per particle, on the job system's threads). CPU only.
  - **Ground snapping** (decals, puddles, leaves settling) is a collision mode that places a particle on the hit surface
    and freezes it.
- **Events and sub-emitters:**
  - An emitter declares events (birth, death, collision, periodic, emit, or a condition such as speed below a value).
    Each names the emitter it spawns from, a count (or a range, or a chance) and what is inherited.
  - The `update` pass appends `{target emitter instance, parent slot in copy B, salt}` with an atomic counter into
    `ParticleKeys.EVENTS` (capacity from the effect, overflow counted and dropped); `spawn args` sorts them (§2.4).
  - The spawn pass reads the parent's attributes from copy B, which `update` just wrote (VFX Graph's "source
    attributes"). There is no payload struct to declare. A world-space child of a local-space parent converts the
    position through the parent's emitter matrix.
  - The child's seed is `hash(parentSeed, event salt, i)`, so children look the same whatever order the atomics took,
    and the sort gives them the same slots.
  - **Nesting:** each level of birth events adds a frame. The depth is limited (4 by default) and cycles are rejected
    when an effect is built.
  - **CPU emitters** run their events in `CpuSimulation`, the same way, in order, and can also spawn into GPU emitters
    of the same effect (as spawn records); GPU emitters never spawn into CPU ones (that would be a readback).
  - **CPU-side events** (an emitter that should start a whole effect, a sound or gameplay) are not read back from the
    GPU. They are CPU events from the emitter runtime (start, loop, end), and the game triggers them directly.
- **Shared bursts** (E22): `effects.burst(asset, ...)` goes into the shared instance of the asset in the burst's
  **cell** (the effect type's cell size, 64 m by default), made on the first burst there and ended once it has been
  empty for its longest lifetime. So each shared instance has tight bounds for culling and significance, an origin near
  its particles, and a capacity from the asset. When a cell's instance is full, CPU spawn demand drops its *oldest*
  survivors (the first in order, skipped by the scan); events still clamp to room.
- **Timing:**
  - Normalized age = `age / lifetime` drives curves (keys, above).
  - Spawn timing is exact within a frame (birth times along the frame); the spawn pass integrates the remainder.
  - Simulation time is the world clock times the instance's time scale: once per rendered frame, delta clamped
    (0.1 s, inside D92's 0.25 s frame cap). Emitters opt into fixed substeps (`substeps(2)` or `fixedRate(60)`) for
    stiff forces and collisions, as Godot's fixed fps and PopcornFX's micro-updates do.
  - **Pause and time scale:** a paused world (D128) or `effects.pause()` gives dt 0; `effects.timeScale(s)` and
    `effect.timeScale(s)` multiply it; both travel in the emitter record, so the GPU needs nothing else. Real-time
    effects (UI) use the app clock instead (`Effects` made on it).
- **Prewarm and warm-up** (a looping fire starts full; a late joiner's effect starts at "now"):
  - CPU emitters run their steps forward in fixed steps, capped.
  - GPU emitters prewarm *in the spawn pass*: the runtime emits the spawn records the emitter would have emitted over
    the prewarm time, back-dated; each new particle integrates its own age in up to 16 substeps in its thread. That is
    one pass, however long the prewarm. During prewarm, events are not sent and collision with depth is skipped (no
    depth for the past); modules that need neighbours are not prewarmed.
  - Prewarm is capped per frame (particles prewarmed), with a warning in the statistics when it costs too much; an
    effect over the cap starts over a few frames while it is not yet visible.
- **Bounds:**
  - the effect's authored bounds (or bounds from its shapes, speeds and lifetimes, estimated when it is built) for
    CPU significance and culling;
  - GPU bounds, computed in `update`, for drawing (per-view culling of a whole emitter instance) and for mesh particle
    instances.
  Nothing waits for a readback.
- **Scalability:**
  - `effects.quality` (low, medium, high, epic) scales spawn counts and capacities per effect type, lowers lighting
    (above) and disables emitters marked for a minimum quality.
  - The global pool budget comes in bytes. Near the budget, the least significant instances get spawn scaling, then
    culling.
  - Instance caps per effect type, with significance kept every frame (Niagara: without a significance handler, caps
    act only at spawn).
  - Half-resolution drawing is per emitter or for all particles at low quality.
- **LOD transitions without popping:**
  - The drawn fraction moves smoothly (over 0.3 s) and each particle compares its hash with it inside a band, fading
    its alpha (and growing the kept ones' sizes) instead of vanishing.
  - A change of spawn scale applies to new particles only; nothing alive is killed by LOD.
  - Distances have hysteresis (10 % by default), so an effect at the boundary does not flicker between levels.
  - A reduced update rate (every 2nd or 4th frame, delta summed) is used beyond a distance only, and the draw
    extrapolates positions by velocity times the time since the last update, so motion stays smooth.
  - A paused (culled) instance that comes back catches up as prewarm does, capped.
- **Pooling and reuse:**
  - Effect instances are slots in `Effects` (generations, ids reused).
  - An instance ends when its emitters have stopped and their longest lifetime has passed: the CPU knows it from the
    authored values, so ending needs no readback.
  - Ranges come back to the allocator once no frame in flight reads them; each asset keeps a few freed ranges warm, so
    effects spawned often (muzzle flashes) reuse them instead of searching the allocator.
  - Module programs and curve blocks are kept per asset while instances use it, and some seconds after.

### 2.8 Choices in assets, and the kernel

E10, E14, E22, E1 and E17 make many things choosable. They are expressed as *which modules and renderers an emitter
lists*, never as flags inside one module, and they map to a small, fixed set of compiled programs:
- **Simulation kernels:** one interpreting kernel (E4) compiled in a few variants by capability, because a GPU's
  register allocation is the largest path's for every wave: the **base** variant (everything but ray queries), the
  **rays** variant (with the ray-query collision module; only where the device traces rays), and, from step 20, one
  generated with the game's plugin modules. The CPU bins each chunk into its variant's list; each variant's passes are
  indirect over its list. A chunk is one emitter, so the opcode stream is uniform per wave.
- **Draw pipelines:** renderer (billboard, ribbon, trail, beam) × lighting (unlit, vertex, pixel, six-way) × target
  (scene color, WBOIT, half resolution): at most 48, created when the graph builds and only for what loaded effects
  use. Soft fade, flipbooks and alpha-to-add are branches on per-emitter values, not pipelines.
- **Targets:** CPU emitters run the Java twins of the same modules; a module without a twin is refused on a CPU
  emitter when the effect is built.
- **Fallbacks** are explicit chains in the asset (`orElse`), resolved once per device when the module program is
  encoded; quality lowers choices through the effect type. Nothing silently changes an effect's look.

### 2.9 CPU and GPU emitters (E1), and emitter state on the GPU (E2)

**CPU emitters.**
- The target is fixed per emitter in the asset. One effect may mix them: a CPU emitter of grenade shards that gameplay
  reads, with GPU sparks and smoke.
- `CpuSimulation` runs the CPU-capable modules in Java, emitter by emitter on the job system, with the same module
  order, the same hash, the same survivor copy (order kept) and the same event sort. Its records are uploaded into
  their ranges each frame, so drawing, sorting and LOD do not know where a particle was simulated.
- Do the same asset's CPU and GPU emitters behave alike? Their random values are bit for bit the same (§2.3), their
  spawn counts too (both from `EffectRuntime`). Their floats are not: the GPU fuses multiply-adds and approximates
  transcendental functions. Non-chaotic modules (ballistics, drag, curves, orbits) stay within a small tolerance;
  chaotic ones (bounces, curl noise over seconds) diverge visibly over time. So: *alike, not identical*. Step 4
  checks GPU against Java within tolerances per module.
- Cost: every built-in CPU-capable module twice. The reviewer note on E1 keeps it bounded.
- CPU emitters suit small counts (hundreds to a few thousand per emitter): the upload is 80 bytes per particle per
  frame, a million CPU particles would be 80 MB a frame.

**Emitter state on the GPU (particles as emitters).** Built, as the reviewer note on E2 recommends, from what exists:
- A parent emitter lists an **emit** updater (`Emit.rate(Range.of(20, 30)).into("trail")`, `Emit.perMetre(...)`,
  `Emit.burstAt(age, n)`). Each parent particle keeps an accumulator (fixed point) in a side array and, each frame,
  appends emit events for its child emitter in `update`; the events spawn in the same frame (§2.4).
- The child emitter is one range shared by all its parent's particles: one sort, one draw, a ribbon per parent (the
  parent's spawn index as ribbon id): a trail behind each of 100,000 sparks.
- Timing per GPU emitter (delay, duration, bursts) is by the parent particle's age; loops are not offered.
- **With the CPU runtime:** the CPU knows only the parent instance. LOD (spawn scale, drawn fraction), caps, stop,
  kill, pause and time scale act on it through its emitter record, and the parent's children follow. Capacity: the
  child's is authored (parents × rate × lifetime), checked when the effect is built, and overflow is counted.
- **Events:** GPU emitters reach no CPU code: no sounds or gameplay from them (that would be a readback). A
  gameplay-relevant source is a CPU emitter.
- **Networking:** only the parent instance replicates; the rest follows from seeds.
- **Determinism:** accumulators in fixed point, event sort, seeds: identical on one GPU and driver (E11).

### 2.10 Networking and determinism

- **Effects are cosmetic** unless an emitter is a CPU emitter gameplay reads. A server never simulates GPU particles. It
  replicates *what plays*: effect asset id, node (or world pose), seed, start tick, user parameters at start, stop and
  kill. These are `Effects` changes in the change log, sent like any other scene change. Gameplay never depends on GPU
  particles (§4.19). A server that needs a CPU emitter's particles runs `CpuSimulation` (it is in `scene-effects`).
- **The same look on every client:**
  - the same seed;
  - spawn counts from the deterministic CPU runtime: time in integer ticks of the world clock, rates and carried
    fractions in 32.32 fixed point, `StrictMath` where it decides counts; GPU emitters' accumulators use the same
    fixed point (two 32-bit words where the device lacks 64-bit integers);
  - per-particle random values from hashes, independent of ordering; events sorted before they spawn.
  On the same GPU and driver, the same inputs give the same frames. Across GPUs the floats differ (fused multiply-add,
  transcendental functions): close, not bit-identical.
- **Bursts** replicate as events (asset, position, normal, payload, tick) when they are cosmetic; most games trigger
  them locally from replicated gameplay events instead.
- **Late joiners and replays:** the start tick lets a client prewarm a looping effect to "now" (capped), and skip
  one-shot effects that have already ended. Replays record effect events, not particles.

### 2.11 Budgets and costs

**Memory per particle slot:** 80 B × 2 copies = 160 B; side arrays as used (orientation 16 B, previous position 12 B,
trail history 12 × N B, each × 2); sort keys and indices 8 B × 2 per sorted particle, per pair of eyes or view; events
12 B per event slot. Emitter records about 256 B each; curve keys a few hundred bytes per effect.

| Target | Pool budget (default) | Alive particles in a heavy scene | Notes |
|---|---|---|---|
| RX 9070 XT, desktop 1920 × 1080 to 4K | 256 MB (1.6M slots) | 1M | `ParticleBenchmark` sets the real numbers in step 18. |
| Quest 3 through Quest Link or SteamVR, same GPU, 2 × 2064 × 2208 at 90 Hz | 128 MB (0.8M slots) | 250k to 500k | 11.1 ms a frame for everything; the link's encoder needs headroom. |
| lavapipe (CI) | 4 MB | 10k | Software: tests keep counts small; same code paths, async off. |

**Overdraw on the headset.** Two eyes of 2064 × 2208 are 9.1 Mpixels a frame. A blended layer over the whole of both
eyes reads and writes RGBA16F: about 146 MB a frame, so eight full-screen layers of smoke are about 1.2 GB a frame, some
1.8 ms at the RX 9070 XT's 640 GB/s before any shading. Per-pixel lighting multiplies the shading on top. Hence the
size caps, half-resolution emitters and the LOD above, and a budget of about four full-screen layers on average in VR.

**Simulation.** A particle's update reads and writes 80 B plus its side arrays: a million particles move about 160 MB a
frame, about 0.25 ms of bandwidth on the RX 9070 XT, plus the modules' arithmetic. The sort of 1M keys (4 passes of
4 bits on 16-bit keys, 8 on 32-bit) is the next cost; it runs once per pair of eyes, not per eye.

**Standalone Quest 3 (Adreno 740)** is not a target: engine-xr drives PC runtimes, and the engine has no Android build.
If it becomes one, the tile-based GPU makes blending cheap and bandwidth to memory dear, async compute rarely helps,
and budgets fall to tens of thousands of particles; the design still fits (CPU emitters for small effects, fewer
layers), but half-resolution particles and depth history copies would need rethinking for tile memory.

**Shared simulations (E23, step 18a).** A hundred torches on a map need not be a hundred simulations. An asset marked
shareable gets K variants (default 6, per asset), each simulated once in local space at its own seed and time offset so
neighbours don't flicker in step; every instance in the world becomes a *placement*, a transform and the variant it
shows, and the draw applies the placement's transform in the vertex shader. The simulation and the CPU's emitter work
fall from one per instance to K, plus a small record per placement; drawing (pixels, and the sort of sorted emitters,
per placement) costs as before.
- **Eligibility is derived from the modules**, not trusted from the author: local-space emitters, noise and curl noise,
  drag, uniform gravity and wind, vortices about the effect's own axis, flipbooks, ribbons and trails in local space are
  shareable; world-space emitters only on static nodes (where world and local space agree); collision of any kind
  (depth, terrain, colliders, rays), forces at world positions (attractors, vector fields placed in the world),
  inherited velocity, events spawning into the world, and parameters set per instance (game code, `EffectBindings`)
  make an asset unshareable, and `effects.play` then gives it simulations of its own as today, with a debug note.
- **With LOD**: placements near a viewer may keep simulations of their own and fall back to a shared variant past a
  distance, the swap hidden by the LOD fades; a placement's start time picks the variant whose phase is closest, so a
  torch lit in front of the player starts at its beginning.
- **API**: `EffectData.shared(k)` on the asset; `effects.play` returns a handle as for any instance (stop, move,
  scale work per placement; per-instance parameters are refused on shared assets).

**Scope per step** is in section 5; the largest risks are steps 4 (the kernel), 7 (ribbons) and 16 (mesh particles).

### 2.12 Editor, hot reload and debugging

- **Hot reload:** an asset changed in a tool (or reloaded from a file) is a new `EffectData`; `effects.replace(old,
  new)` restarts every instance of the old with the new, keeping handles, nodes and parameters, and keeps its ranges
  when the capacity still fits. Encoding a module program takes microseconds and compiles nothing (E4). Plugin modules
  in Slang need a rebuild of the kernel variant, as material types do.
- **Seeking:** effects are deterministic, so an editor scrubs a timeline by restarting an instance and prewarming it to
  the time shown; looping previews need no recorded frames.
- **Preview without a GPU:** the CPU-capable part of an effect runs in `CpuSimulation` in tests and tools.
- **Debug views** (a technique for the editor's view): CPU and GPU bounds as boxes, emitter origins and sockets, alive
  and overflow counts per emitter (from the readback), overdraw heat map, and which kernel variant and group each
  emitter took.
- **Validation** reports, at build: unknown modules and fields with "did you mean…", modules not allowed on the target,
  event cycles and depth, curves over the key cap, capacities below rate × lifetime + bursts, collision chains with no
  method for the device.

### 2.13 Particle modules from their Slang structs (E24)

Step 20 left a module in up to five places that had to agree by index: its Slang code in the kernel's switch, an
`OP_*` constant, the encoder's opcode switch, its `ModuleType` in scene-effects and, for a game's module, a
hand-written `ParticleModules` dispatch. E24 makes the Slang struct the one place.

**What a module is.** One Slang struct with `[ParticleModule("name")]`: its fields are its parameters (`float` a
binding, `ModuleColor`, `ModuleCurve`, `ModuleGradient`, `ModuleTexture`; each initializer its fallback; read in
alphabetical order, the salts' order), its functions its stages (`init`, then each step `force`, `after`, `collide`,
`update`, `over`), `[ModuleCapability("RAY_QUERY")]` where it needs the device's ray queries, and `[BuiltInTwin]` or
`[ModuleTwin("java.Class")]` where CPU emitters may have it. The build (truesim.slang, a collecting mode of the
material-type generator, from the templates engine-render ships in `particle-modules`) numbers every module of the
project and its libraries (the libraries' first, as their `particle-modules.index` numbers them), and generates the
opcodes, a switch per stage whose cases read each field into the struct and call its function (the per-wave uniform
interpreter of E4, no specialization constants), the `ModuleType`s and a Java class listing every module by opcode
with its stages. A game's project also gets its kernel variants (base and rays) with the built-in modules and its own.
Duplicate names, too many fields, unknown field types, a module without a stage function or with stages at both
birth and step fail the build.

**Where module types live.** Options weighed:

| | For | Against |
|---|---|---|
| A. Slang is the source; the build generates the `ModuleType`s; scene-effects mirrors the built-in ones and the engine checks the mirror (chosen) | A game's module is one struct (and its twin); a GPU-only built-in is one struct; nothing to keep in step by index | The built-in types are written twice (Slang, scene-effects' `ModuleRegistry.BUILT_IN`); a mismatch is found by the engine (its `ParticleModules` refuses it, a test runs it), not by scene-effects' own build |
| B. scene-effects is the source; the engine's build fails where the Slang disagrees | scene-effects alone defines the data | Every module, a game's included, needs a Java `ModuleType` and a Slang struct: two places, the brief's opposite; the build would have to load scene-effects' classes |
| C. The built-in modules' Slang moves into scene-effects | One source for the built-ins | Their code needs the engine's modules (noise, pool meshes, bindless textures): it cannot live in scene |

scene-effects must stay usable without the engine (servers build and validate effects, `CpuSimulation` runs the
built-in twins), so it keeps knowing every built-in type: A keeps that mirror and checks it, at the cost of writing a
built-in's fields twice. A built-in with a CPU twin needs scene-effects anyway (its Java code); a GPU-only built-in
changes the mirror only so that scene-only users know it.

**Java twins.** A game's module may have a `ModuleTwin` (scene-effects): `[ModuleTwin("com.example.GustTwin")]`
makes its type run on both targets, and `CpuSimulation` runs the twin where the kernel runs the struct (at birth, or
after the motion and collision), on a `TwinParticle` (the particle, its float fields evaluated, the step). Without
one, the module is GPU only and CPU emitters naming it are refused, as before. The built-in twins stay inside
`CpuSimulation` (`[BuiltInTwin]`).

**Adding a module** (the test's `game.gust`, engine-render's test/particlegust.slang):

```slang
import particles.particle;

/** Pushes particles along +X by its strength, in m/s^2. */
[ParticleModule("game.gust")]
[ModuleTwin("com.example.game.GustTwin")]   // optional: CPU emitters may have it
public struct Gust
{
    public float strength = 1;               // a parameter: a binding, 1 where a module leaves it out

    public void update(inout ParticleStep s, inout Particle p)   // its stage: each step, after the collision
    {
        p.velocity.x += strength * s.h;
        p.position.x += 0.5 * strength * s.h * s.h;
    }
};
```

```java
public final class GustTwin implements ModuleTwin {   // optional
    public void run(TwinParticle p) {
        float a = p.value(0);                         // fields in alphabetical order
        p.position(p.x() + 0.5f * a * p.h() * p.h(), p.y(), p.z());
        p.velocity(p.vx() + a * p.h(), p.vy(), p.vz());
    }
}
```

```kotlin
slang { particleModules = "com.example.game.GameModules" }   // once per project: the generated listing
```

```java
particles.modules(GameModules.MODULES);
EffectData leaves = EffectData.builder().emitter("leaves", e -> e.update(Module.of("game.gust").with("strength", 2)))
    .build(GameModules.MODULES.registry());
```

A built-in module is the same struct in engine-render's particles/modules, its type added to scene-effects'
`ModuleRegistry.BUILT_IN` too (and its twin to `CpuSimulation`, if CPU emitters may have it).

---

## 3. What changes in existing code

- **`GpuScene`** (E15):
  - a *GPU-written range* of instance ids, claimed in one block (`reserveGpuRange(n)`, one `version()` change): the
    CPU never writes them, the upload never scatters into them, and the GPU writes live records or holes (mesh word 0,
    which culling already skips). The range lies below `highWater()`, which the instance pass and the top level count
    to;
  - the range is flagged *GPU-written*, and `RayTracing`'s instance pass gives its records mask 0 and an empty bottom
    level (pitfalls: an instance going between active and inactive forbids a refit), so the top level's
    `version()`-based skip stays right while particles move;
  - previous transforms (for motion vectors) of that range come from the particle pass per slot, since slots do not
    follow particles; occlusion history per id works, only less efficiently for these ids.
- **`SceneGeometry` / `GeometryProvider`:** nothing blocks a third provider any more (review 2026-10-31 item 9 is
  fixed: `renderer()` takes the first provider's, and the constructor checks they share one). Opaque and masked
  billboards become a provider later. Mesh particles need no provider (they are instances).
- **Transparency:** `ParticleDraw` orders itself `at(TRANSPARENT).after(Transparency.ID)`, and emitters drawn before
  glass in a second technique `at(TRANSPARENT).before(Transparency.ID)`. Emitters opting into order-independence draw
  into `BlendedTargets` (`Kind.WEIGHTED`) through `GeometryProvider.drawBlended`, which every transparency technique
  already calls for each provider; a small particle provider (no opaque draw) carries it. The split glass order needs
  the transparency technique to write the nearest blended depth. A test runs three frames or more (pitfalls: per-phase
  lists).
- **Keys:** `ParticleKeys.PARTICLES` and `ParticleKeys.EVENTS` buffers; a depth history of the collision view
  (`g.history`), made only when an emitter collides with depth.
- **Ray tracing:** the ray-query collision variant reads the frame's top level after its build (`TopLevel`); mesh
  particles stay out of it (above).
- **`Lights`** (step 17b): a GPU-written light range for particle lights; `ClusteredLights` reads a GPU count instead of
  `lights.count()`.
- **Per-instance custom data** (tint, alpha per mesh particle): the "buffers by id" README promises.
- **`SceneSync`'s interpolation** (D91, when built) and `EffectSync` share the blend factor and previous transforms, so
  emitters follow drawn nodes.
- **scene-animation** (optional): `Animator.node(Joint)`.
- **frame-graph-v2 §4.19** is changed, not only refined:
  - kept: GPU simulation for the bulk, the spawn ring, game-written CPU records, extension by modifying particle keys,
    fixed render methods, budgets, and both "don'ts" (no switching one emitter between CPU and GPU as a fallback; no
    readback for gameplay);
  - reversed by E1: "without a second simulator": CPU emitters simulate the CPU-capable modules in Java;
  - changed: mesh particles are `GpuScene` instances instead of a `GeometryProvider` (E15); ribbons are in the first
    version (E13); blended particles have their own sorted pass with WBOIT and glass order per emitter (E8).
- **Application API:** effects advance in the effect sync after `LATE`, on the world clock (D88, D128), scaled per
  instance; no new phase is needed.

---

## 4. Options weighed (the decisions are E1 to E22 above)

"Recommended" marks the first recommendation; a **Decided** line follows where the user chose otherwise.

**P1. Where particles simulate**
1. **GPU only; gameplay particles CPU-fed into the same renderers** (recommended). One simulator per behaviour, as
   §4.19 decided. Cons: no CPU fallback for GPUs without compute (none at Vulkan 1.3); authors can't make a particle
   gameplay-relevant without code.
2. CPU and GPU emitters, one description (Niagara, Shuriken plus VFX Graph). Pros: gameplay reads particles; tools
   preview without a GPU. Cons: every module twice, behaviour drift, tests doubled.
3. CPU only, uploaded each frame. Pros: deterministic, simple. Cons: about 100k particles at best; per-frame upload
   bandwidth; not what modern effects need.

**Decided: E1, option 2 with one target per emitter** (no switching), a declared CPU module subset, the Java
simulator as the GPU's reference (§2.9).

**P2. Where emitter logic runs**
1. **CPU, in `scene-effects`** (recommended). Pros: deterministic, testable, replicable; the game's inputs are on the
   CPU; Niagara, VFX Graph and Godot agree. Cons: spawn records cross to the GPU each frame (a few bytes per emitter).
2. GPU (spawn counts computed by a pass). Pros: nothing per emitter on the CPU. Cons: LOD, timing and events from the
   game need GPU state anyway; harder to test; no server side.

**Decided: E2, both:** the CPU runtime by default; emitter state on the GPU for particles as emitters, built as
emitting particles through events (§2.9).

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
   update writes each particle once either way. Cons: two buffers (2 × 80 B × capacity); a small scan pass.
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

The review adds a per-emitter glass order to option 1 (before, after or split; §2.5 and the reviewer note on E8).

**P9. Sub-emitters and events**
1. **GPU event buffer; children spawn at the next spawn pass, reading the parent's attributes; one generation per
   frame** (recommended). Pros: GPU-native; no payload declaration; bounded; deterministic look by seeds. Cons: each
   nesting level costs a frame; overflow drops events (counted).
2. Same-frame chains (a spawn pass per generation). Pros: no latency. Cons: a pass per level per frame; more barriers.
3. Events through CPU readback (Niagara's CPU events). Pros: events reach gameplay. Cons: 2 to 3 frames late,
   nondeterministic; ruled out by §4.19.

The review adds a sort of the events by (target, parent slot) before they spawn, without which option 1's order
depends on atomics (§2.4).

**P10. Collision**
1. **Analytic scene colliders + terrain height field + previous-frame depth; ray queries later** (recommended). Pros:
   covers ground, walls near the player and authored shapes; cheap; all on a 1.3 GPU. Cons: depth is wrong off screen;
   no arbitrary geometry until ray queries.
2. A global SDF of the scene. Pros: smooth, off screen, also for attraction. Cons: a whole system to build and keep
   updated (clipmaps, mesh SDFs).
3. Ray queries only. Pros: exact. Cons: only where ray tracing exists; cost per particle.
4. CPU physics (common-collision) for CPU particles only. Pros: exact, gameplay. Cons: not for GPU particles.

**Decided: E10, every method, chosen per emitter** as a chain with fallbacks (§2.7); SDF later.

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
1. **No: billboards first; trails (per-particle history) and ribbons (spawn order) in a later step** (recommended).
   Pros: the core lands sooner; compaction already keeps the order. Cons: no sword trails at first.
2. Yes, from the start. Pros: complete. Cons: a larger first step, renderer and LOD details before the core is proven.

**Decided: E13, option 2.** Ribbons, trails and beams are their own step (7), after billboards, so each PR stays
reviewable.

**P14. Lit particles**
1. **Unlit and emissive first; lit per vertex from the light list with sun shadow next; per-pixel normal-mapped and
   six-way after** (recommended). Pros: the cheap path first; six-way gives the best smoke for the cost. Cons: smoke
   looks flat at first.
2. Per-pixel lit from the start. Pros: quality. Cons: overdraw × light loop is the worst cost on a headset.
3. Froxel injection (Frostbite). Pros: the best volumetric look. Cons: needs volumetric fog the engine doesn't have.

**Decided: E14, chosen per emitter** among unlit, per vertex, per pixel and six-way (one step, 17); froxels later.

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

**Decided: E17, option 1, with world or local space per emitter.** The origin is per effect instance (per cell for
shared bursts).

**P18. Curves and gradients**
1. **Baked into a curve atlas texture, sampled by normalized age** (recommended). Pros: constant cost; any key count;
   shared by instances. Cons: 256 samples smooth sharp steps (a step curve can be flagged nearest).
2. Keys evaluated in the shader. Pros: exact. Cons: a loop per curve per particle.

**Decided: E18, option 2,** with keys cached per chunk, folding and a key cap (§2.7).

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

**Decided: E22, both, per call;** shared instances per asset and cell (§2.7).

---

## 5. Steps (a PR each, tested, with the sample content that checks it)

Each step builds on the ones before it and leaves both repositories building and releasable. Scene steps come first
and are device-free; engine steps depend on the scene release that has their types.

| # | Repo | What | Checked with |
|---|---|---|---|
| 1 | scene | `scene-effects` data: `EffectData`, emitters (target, space, timing, spawn, modules, events, renderers), module records by name with targets and capabilities, `FloatCurve`/`ColorGradient` keys (cap 32), ranges, bindings, `EffectType`, validation (cycles, nesting depth, unknown modules with suggestions, modules not allowed on a target, capacity below demand); `Effects` store with change log, handles (generations, inert when ended), time scale and pause | Device-free tests: building, equality (`ref()`), every validation error, change log order, handles of ended and reused slots |
| 2 | scene | `EffectRuntime`: delay, duration, loops, rates with carried fractions in fixed point, bursts across loop ends, per-distance spawning, birth times, prewarm records, end time, significance and caps with hysteresis, LOD levels, quality scaling, seeds, shared-burst cells | Device-free: counts per frame against closed forms at 30, 60, 90 and 144 Hz and with hitches; identical counts whatever the frame split of the same ticks; same results on any thread count; zero bytes allocated per frame |
| 3 | scene | `CpuSimulation`, the Java reference (E1): the first modules (shapes: point, sphere, box, cone, disc; lifetime, velocity, gravity, drag; over life: size, color), the hash, the survivor copy in order, world and local space; `ParticleCursor`; `ParticleWorld` interface (unused until step 12) | Device-free: ballistic positions against analytic ones; order kept across deaths; hash values against fixed vectors (the same vectors step 4 checks in Slang); allocation-free per frame |
| 4 | engine | Pool, generated `Particle`, emitter table, spawn ring, `CurveKeys` with the per-chunk cache, module programs; count, scan, update, spawn args, spawn, args in one group on the graphics queue; the modules of step 3; world and local space; statistics readback | lavapipe and the RX 9070 XT: GPU positions against `CpuSimulation` within tolerance per module; spawn counts and order; overflow counted; seeds equal in any order; synchronization validation clean over three or more frames; a curve benchmark (8 curves × 8 keys, 1M particles) |
| 5 | engine | `ParticleDraw`: per-view cull, billboards (position-facing, view-plane on desktop, velocity, axis), premultiplied alpha and additive, soft fade (depth and volume), flipbooks with frame blending, unlit; after `Transparency.ID`; emitters ordered as wholes; dynamic resolution region | Screenshot tests (sprite coverage, soft edge against a wall, additive over dark); three-frame test with occlusion phases; a render region smaller than the image; one thread and four draw the same image |
| 6 | engine | CPU emitters on the GPU side (E1): `CpuEmitters` upload into the pool, game-written records through the generated writer | One asset with a CPU and a GPU emitter draws the same image within tolerance; allocation-free per frame |
| 7 | engine | Ribbons (spawn order, head at the emitter, ribbon ids with the second key, stride LOD), trails (history per particle, its cap), beams (E13); texture modes (stretch, tile), width over length | A ribbon behind a moving emitter, continuous across survivor copies and chunk boundaries, with no first-frame gap; a beam between two nodes; trails on 10,000 sparks timed |
| 8 | engine-scene, app | `EffectSync`: attachment to nodes and joints (sockets, offsets), world and local space, inherit velocity, transforms as drawn (D91 when built), origin rebasing, stop and kill, time scale and pause, pooling (warm ranges, programs kept), hot reload (`effects.replace`); `Effects` plugin; demo: torch fire, sparks, smoke | An effect 7,000 km out draws like at the origin; a paused world freezes effects while views draw; a replaced asset restarts its instances; zero bytes allocated in the scene benchmark's column |
| 9 | app | `EffectBindings`: animation events to effects at joints, curves to parameters; the demo's Fox kicking up dust | Fox walking with dust at each foot on its foot-down events; a curve driving intensity, frame by frame |
| 10 | engine | Sorting: `ParticleSort` request, indirect radix sort per view, sorted emitters only, one sort per pair of eyes (equal requests), center-facing billboards in VR | Two overlapping colored emitters in both orders; `StereoBench` with particles: one sort for both eyes, identical orientation; sort time at 10k, 100k and 1M |
| 11 | scene, engine | Forces: curl noise, vortex, attractors, point and line forces, orbit, vector fields (3D textures), speed limits, wind; each CPU-capable one in `CpuSimulation` in the same PR (one scene release, then the engine) | Analytic checks (orbit radius kept); GPU against Java within tolerance |
| 12 | scene, engine | Collision (E10), part one: analytic colliders on nodes, terrain height field, previous-frame depth (history, reprojection, the collision view), `ParticleWorld` for CPU emitters, chains with `orElse`; bounce, stick, slide, kill, ground snapping | Bounce height by restitution; particles resting on terrain from `TerrainHeights`; rain stopped by a roof (depth); a CPU emitter hitting a common-collision box |
| 13 | engine | Collision, part two: the rays kernel variant, ordered after the top level, with its fallback | Exact hits off screen on the RX 9070 XT; the same effect falls back to depth where `rayTracing()` is false (lavapipe skips the traced half, as `ShadowsTest` does) |
| 14 | engine | Events and sub-emitters: birth, death, collision, periodic; source attributes; sorted events; nesting depth; E22's bursts: shared per asset and cell (oldest dropped when full) or instances of their own | Fireworks (burst on death of a burst); 1,000 impacts in a frame in a few draws; the same frames in two runs (event order); dropped events counted, not crashing |
| 15 | engine | Mesh and skinned emission: static surface, vertices, volume by alias tables over the finest level; the deformed copy of a skinned mesh, ordered after deformation; emission from bones; texture and density masks | Particles uniform over a mesh's area (histogram test); fire on BrainStem's or the Fox's skin as they move |
| 16 | engine | Mesh particles (E15): `GpuScene` GPU-written range, kept out of ray tracing (mask 0), the early group on the graphics queue before culling, previous transforms per slot, shadows per emitter | Debris rocks casting shadows; `VisibilityBufferShading` and forward draw the same image; occlusion over 3+ frames; the top level unchanged while particles move; the main group still on the async queue |
| 17 | engine | Lighting per emitter (E14): per-vertex light list and sun shadow; per-pixel normal-mapped; six-way lightmaps; lowered by quality | 1,024 point lights screenshot (`Screenshots.renderLights` style); smoke lit from one side; cost on both shading techniques |
| 17b | engine | Particle lights: the light module, a GPU-written range in `Lights`, `ClusteredLights` binning a GPU count, budgets per emitter and global (brightest kept), no shadows | Sparks lighting the ground and a rock beside them (screenshot); a burst over the global budget keeps the brightest and stays within it; `ClusteredForwardShading` and `VisibilityBufferShading` draw the same light; no CPU readback |
| 18 | both | Scalability: effect types enforced, budgets in bytes, LOD transitions (faded fractions, hysteresis, extrapolated reduced rates), half-resolution particles with depth-aware upsample per eye, `effects.quality`; the main group on the async queue measured against the graphics queue; debug views | `ParticleBenchmark` (1M particles: update, sort, draw; an overdraw scene of large smoke sprites) on the RX 9070 XT and at Quest 3 eye size through `StereoBench`; README tables dated |
| 18a | both | Shared simulations (E23): eligibility from modules (scene, with `CpuSimulation` placements), K variants per asset with seeds and time offsets, placements as transform and variant drawn through the variant's particles, placements in `ParticleDraw` and the sort, near placements with their own simulation through LOD | 1,000 static torches cost 6 simulations (CPU and GPU timings against unshared); neighbouring placements out of phase; an asset with collision or bindings refused sharing and simulated per instance; a placement moved, stopped and scaled alone; the swap at the LOD distance without a pop |
| 19 | engine | Emitter state on the GPU (E2) as emitting particles: the emit updater, fixed-point accumulators, child ranges per parent, ribbons per parent | 100,000 sparks each with a trail; counts against `EffectRuntime`'s closed forms for one parent; stop and LOD of the parent reach the children |
| 20 | engine | Plugin modules: `[ParticleModule]` Slang structs registered like material types (generated records, name ids), a generated kernel variant; `modify(PARTICLES)` techniques; optional Java twins for CPU emitters | A game's own force module in a test project, as `CustomPassTest` does for passes |
| 21 | engine | Glass order (open question 1): before and split; reactive mask and mesh-particle motion vectors when TAA lands | A fire behind a window tinted by it; sparks in front of it untinted; with TAA, no ghosting behind sparks |
| later | engine | Distortion, decals, neighbour grids and simulation stages, SDF collision and emission, stateless emitters, froxel injection, smoke shadows, variable rate shading for particles | Each with its own design note when the engine has the system it depends on |

Each engine step keeps `docs/pitfalls.md` in mind:
- per-frame Vulkan calls go through `Commands`;
- buffer pointers are loaded one at a time (the integrated GPU);
- a layout shared by Java and Slang is generated once;
- tests run with validation, on lavapipe too, over three or more frames, and the ray tracing ones once with
  GPU-assisted validation;
- results that come from the GPU are asserted after enough frames and `waitIdle`;
- an instance never goes between active and inactive in a refitted top level;
- the scene benchmark's bytes-allocated column stays 0.

---

## 6. Review (2026-11-02)

A high-effort review checked the design against the engine (`origin/main` at #355: `GpuScene`, `SceneData`,
`Deformation`, `RayTracing`, `DeformedBottomLevels`, `BottomLevelUpdates`, the transparency techniques, `Lights`,
`ClusteredLights`, `SceneSync`), scene-animation, scene-collision, the application API's decisions and the cited
sources (Niagara's ray-traced collision, Unity's six-way lighting, Niagara lightweight emitters and data channels
re-read). E1 to E22 are unchanged; reviewer notes sit under the decision table.

**What changed in this document:**
- Sections 2 and 3 rewritten for E1 to E22: no "GPU only", no curve atlas (keys with a per-chunk cache), ribbons in
  v1, collision and lighting per emitter with fallback chains, world and local space with one origin per effect
  instance, bursts per call. Section 4 marks each decision that differs from its recommendation.
- Wrong claims fixed: review item 9 (`SceneGeometry` and its providers) is already fixed in the engine; mesh particles'
  ids are not stable per particle (the survivor copy moves them), so occlusion history and previous transforms are not
  "free"; `BlendedTargets` needs no new hook (`GeometryProvider.drawBlended` exists); the top level skips rebuilds by
  `GpuScene.version()`, which GPU writes do not change; `Animator` has `node(int)`, not `node(Joint)`, and animation
  listeners get the animator's first node, not a joint; frame-graph-v2 §4.19's "without a second simulator" is
  reversed by E1, not refined.
- Determinism: atomic event appends made children's slots, ribbons of children and unsorted emitters differ between
  runs; events are now sorted before they spawn (a `spawn args` pass, which the plan was also missing to size the spawn
  dispatch from event counts).
- New design text: emitter groups (early on graphics for mesh particles and rebases, main on async) and the async
  hazards; transforms as drawn under D91 interpolation; shared bursts per cell; CPU emitters (`CpuSimulation`,
  `ParticleWorld`, `ParticleCursor`) and particles as emitters; kernel variants and pipelines for choosable options;
  glass order; soft and volume fade in VR; views, shadow views and motion vectors; LOD transitions; prewarm in the spawn
  pass; pause and time scale; pooling; sockets and bones; animation bindings; handles; hot reload, seeking and debug
  views; budgets for the RX 9070 XT, Quest 3 through a PC runtime, and lavapipe.
- Steps re-cut to 21 PR-sized ones in buildable order: the Java reference before the GPU kernel, ribbons apart from
  billboards, collision in two, animation bindings, GPU emitter logic after events, glass order last.

**Risks that remain:**
- **E1's double implementation** of every CPU-capable module, and drift between them; tolerance tests per module are
  the guard.
- **The kernel's register pressure**: the largest built-in module sets occupancy for all emitters of a variant; step 4
  measures it, and heavy modules may need their own variant.
- **Mesh particles** (step 16) touch `GpuScene`, culling order and ray tracing at once; the riskiest engine change.
- **Async compute may not pay**: the main group's wait before `TRANSPARENT` can lengthen the frame; step 18 measures
  both queues.
- **Overdraw at Quest 3 resolution** stays the dominant cost; content budgets matter more than code.
- **Draw count** of many sorted emitters per eye (a draw each); shared bursts and LOD are the mitigation.
- **Glass**: until open question 1 is decided and step 21 lands, effects behind windows look wrong.
- **lavapipe** runs every path but slowly; GPU-scale behaviour (async overlap, sort at 1M) is only seen on hardware.
- **Ribbons with interleaved ids** need the second sort key; its cost is unmeasured.

**Decided with the user after the review** (all as recommended):
1. **Glass order** per emitter: after glass (default), before glass, or split per pixel by the nearest glass depth.
2. **Mesh particles** simulate in an early group on the graphics queue, before culling; other emitters stay async.
3. **GPU emitter logic (E2)** is emitting particles through events (E9): no second emitter runtime in Slang.
4. **CPU emitters (E1)** use a declared module subset with Java twins; plugin modules are GPU-only unless they bring a
   twin. The Java simulator is also the GPU kernel's test oracle.
5. **Shared bursts** go into one instance per asset and cell (cell size on the effect type, 64 m by default).
6. **Billboards cast no shadows in v1**; mesh particles cast shadows as instances when their emitter says so.
7. **`EffectBindings`** (animation events and curves driving effects) lives in engine-app's `Effects` plugin.
8. **Handles of ended effects** are inert, with `alive()`.

---

## 7. Sources

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
- Re-read in the review (2026-11-02): Niagara's ray-traced collision page (one frame late; falls back to distance
  fields), Unity's six-way lighting page (the two maps' channels), Niagara's lightweight emitters and data channels
  pages (islands: one listener system per bounded region).

## 8. As built

Details the steps settled where sections 2 and 5 left them open.

### Step 1: `scene-effects` data (scene 0.44.0)

- **Built-in modules** (`ModuleRegistry.BUILT_IN`, both targets, no capability): initializers `shape.point`,
  `shape.sphere` (radius, thickness: 1 the whole ball, 0 its surface), `shape.box` (x, y, z), `shape.cone` (angle,
  radius), `shape.disc` (radius), `init.lifetime`, `init.speed` (along the shape's direction), `init.velocity` (x, y,
  z), `init.size`, `init.color`; updaters `force.gravity` (scale of 9.81 m/s² down), `force.drag` (linear, per second),
  `over.size` (a curve), `over.color` (a gradient). Every field has a fallback in its `ModuleType`, so a module may
  leave fields out. Plugins add types with `ModuleRegistry.BUILT_IN.with(type)` and build effects against that
  registry. Module values are `Binding`, `ColorBinding`, `FloatCurve` or `ColorGradient`; each field declares one.
- **Bindings:** float inputs from a constant, a float parameter, a curve over normalized age, a curve of a float
  parameter, a curve of speed or a random range, times a scale; color inputs from a constant, a color parameter, a
  gradient over age or a random point of a gradient. Spawn amounts take only those fixed for the instance at a time
  (constant, parameter, curve of a parameter, random range drawn per loop).
- **Curves:** cubic keys are Hermite segments with Catmull-Rom slopes (one-sided at the ends); the slopes are stored
  with the keys, so the GPU encodes them as they are. Times strictly increase; at most 32 keys, refused when the curve
  is made.
- **Timing:** delay, duration (infinite by default: until stopped), loops (0 forever, 1 by default), loop delay and
  prewarm per emitter. Bursts at or after the loop's duration are refused.
- **Capacity** when not given: the ceiling of rate × longest lifetime, plus each burst times the loops whose particles
  can be alive at once (`ceil(lifetime / (duration + loop delay))`, at most the loop count); with parameters at their
  defaults. Spawning per metre or from events needs an authored capacity. An emitter whose demand is 0 is refused.
- **Ends:** an emitter's particles are all dead by `delay + loops × duration + (loops − 1) × loop delay − prewarm +
  longest lifetime`; an emitter spawned by events, by its parents' end plus its own lifetime. `EffectData.end()` is the
  latest (infinite when anything loops forever), `lingering()` the longest chain of lifetimes after a stop, and
  `burstEnd()` the last burst after its delay plus the lingering, for shared instances.
- **Validation** collects every problem into one `InvalidEffectException`: unknown modules, fields and parameters (with
  "did you mean" by edit distance), fields and parameters of the wrong kind, modules at the wrong stage or on a target
  without them, spawn amounts bound per particle, late bursts, missing or short capacities, emitters spawning nothing,
  duplicate names, events into unknown emitters, into emitters not spawning from events, or from a GPU emitter into a
  CPU one, event cycles (named in order) and nesting deeper than `maxEventDepth` (4 by default).
- **`Effects`** takes the scene only (`new Effects(scene)`); the game's `ParticleWorld` goes to `CpuSimulation`
  (step 3), which is what collides. `play(data, node)` and `play(data, x, y, z)` make instances of their own and return
  an `Effect` handle; `burst(...)` returns the shared instance's id, so a burst allocates nothing. A shared instance
  sits at its cell's center; each burst into it fires its emitters' bursts after their delays as if the effect started
  then (rates do not run in shared instances), and it ends `burstEnd()` after its last burst. Burst calls stay in
  `Effects` (position, normal, payload, time) until the runtime drops them.
- **Time:** ticks of a microsecond. Each effect's time moves by the world delta times its own and the global time scale
  (16.16 fixed point, the remainder carried), not clamped: late joiners and replays compute the same times from the
  start tick. The simulation's integration step is what gets clamped (step 3). An effect whose node is removed stays at
  the node's last position, detached, and stops.
- **Change log** flags: `ADDED`, `STOPPED`, `REMOVED` (ended or killed), `PARAMETERS`, `TIME` (scale or pause), `MOVED`
  (offset, position, attachment, inherited velocity), `SEED`. Default seeds are the PCG hash of a play counter.

### Step 2: `EffectRuntime` (scene 0.45.0)

- **One entry point:** `runtime.update(ticks)` advances the `Effects` (so their ends and times move first), syncs its
  state per slot (an effect new in a slot starts from scratch), decides significance on the calling thread, then
  computes spawns per effect on the job system (each effect writes its own records) and gathers them in slot order.
- **An emitter's own time** is its effect's time minus its delay plus its prewarm, in ticks. Loop k runs from
  `k × (duration + loop delay)` for its duration, while `k` is below the loop count and before the stop. Rates spawn
  `floor(rate × 2^32 × running ticks / (2^32 × 10^6))`, the remainder carried per spawn entry; rate × 2^32 is capped at
  2^60 and accumulated in steps that cannot overflow. Births within a segment are evenly spaced, `1 / rate` apart, the
  first where the fraction reaches one. Random rates and counts draw per loop, salted with the loop and the entry.
- **Bursts** fire at `loop start + time`, in (previous, now] of the emitter's time, and at the very first tick on an
  emitter's first update; counts are rounded, after the spawn scale.
- **Per metre:** the effect's displacement over the frame times the fraction of the frame its emitter ran, in
  micrometres, spawned through the same fixed point, births spread evenly over the frame.
- **Prewarm:** on the first update, the emitter's past is (0, prewarm]; what is older than its longest lifetime only
  uses up spawn indices, the rest spawns back-dated and flagged `PREWARM`.
- **Records:** effect, emitter, count, first spawn index, the first birth's age at the end of the frame, the interval
  between births (each next one younger), flags and, for shared instances, the burst call (its position, normal and
  payload stay in `Effects` until the next update; calls whose bursts have all fired are dropped at the start of the
  next update). A record holds at most the emitter's capacity: the youngest births; the skipped ones keep their
  indices.
- **Significance:** distance to the nearest viewer (0 without viewers). Culled beyond `maxDistance × (1 + hysteresis)`
  while shown, shown again within `maxDistance`; levels go coarser beyond the next level's distance × (1 + hysteresis)
  and finer below their own distance. Caps rank effects of a type by distance (shown ones at distance / (1 +
  hysteresis)) or by age (youngest first), ties by slot, with an allocation-free heap sort. Culled effects of `KILL`
  types are killed, of `PAUSE` types held still in time (the runtime holds them in `Effects`, not logged: culling is per
  client), of `SIMULATE` types spawn on with a drawn fraction of 0. The type's priority is not used yet (budgets come in
  step 18).
- **Spawn scale** is the type's scale at the quality times the level's; emitters below their minimum quality spawn
  nothing. Seeds: `emitterSeed = Hash.combine(effectSeed, emitter)`, `particleSeed = Hash.combine(emitterSeed,
  spawnIndex)`.

### Step 3: `CpuSimulation` (scene 0.46.0)

- **Update:** after `runtime.update`, `simulation.update()` follows each effect slot (an effect new in a slot gets
  particle arrays for its CPU emitters, of their capacities, and its origin), then per CPU emitter: survivors age and
  move by the effect's time since the last update, clamped to `MAX_STEP` (0.1 s); then the frame's records spawn, in
  order, while there is room (the rest is counted as dropped). Effects run in parallel on the job system, each alone.
- **Survivors:** a particle dies when `age + dt >= lifetime`; the others are copied down in order (the survivor copy,
  done in place: the same order as the GPU's copy into the second buffer).
- **Spawning:** a newborn starts at its frame's origin at rest with lifetime 1 s, size 0.1 and white, then runs the
  initializers in order; one already dead at the end of the frame (`age >= lifetime`) is not added. World-space ones
  are placed where the emitter was at their birth (between its last and current positions by the birth time), turned
  by its rotation, as float offsets from the effect's origin (its position when it started); a burst call's particles
  at the call's position, +Y turned to its normal. Then each moves on by its age under the updaters.
- **Motion** is exact for gravity `a` and linear drag `k` (summed over the updaters, each evaluated at the particle's
  normalized age at the start of the step): `v' = a/k + (v - a/k) e^(-kh)`, `p' = p + h a/k + (v - a/k)(1 - e^(-kh))/k`,
  or `p + vh + ah^2/2` without drag. Local-space particles take world down turned into the emitter's frame.
- **Over life:** `over.size` and `over.color` multiply the size and color at birth (kept per particle, a side array on
  the GPU) by their curve or gradient at the normalized age; several multiply together.
- **Shapes** (in the emitter's frame, +Y up; draws by salt offset): point (direction uniform on the sphere: `y = 1 -
  2u8`, turn `2π u9`); sphere (direction as the point's, radius `R cbrt((1-T)^3 + (1 - (1-T)^3) u10)`); box (`(u8 -
  0.5) x`, `(u9 - 0.5) y`, `(u10 - 0.5) z`, direction from u11, u12); cone (on the disc of its radius, then direction
  `cos α = 1 - u10 (1 - cos angle)`, turn `2π u11`); disc (`ρ = R sqrt(u8)`, turn `2π u9`, direction +Y).
- **Salts:** a module's draws use 16 salts from `16 × its place in its list`, updaters from 4096 on; its fields take
  the first, in alphabetical order of their names, and its own draws (shapes) take 8 on. A particle's seed is
  `Hash.combine(Hash.combine(effect seed, emitter), (int) spawn index)`.
- **The hash vectors** are in the jar as `com/truesim/scene/effects/hash-vectors.csv` (main resources of
  `scene-effects`, so the engine's step 4 reads them from the dependency), checked by `CpuSimulationTest`. A few:

  | Function | Inputs (hex) | Result (hex) |
  |---|---|---|
  | `pcg` | 00000000 | 07bb2fe2 |
  | `pcg` | 0000002a | 48f432ff |
  | `pcg` | ffffffff | e62a4902 |
  | `combine(seed, salt) = pcg(seed + pcg(salt))` | 00000001, 00000002 | 7deda5d8 |
  | `combine` | 12345678, 9abcdef0 | 49071dc7 |
  | `random(seed, salt) = (combine >>> 8) × 2^-24`, as float bits | 00000007, 00000003 | 3e8f4856 |
  | `random`, as float bits | deadbeef, 00001000 | 3dc47048 |

- **`ParticleCursor`** reads one CPU emitter's particles in spawn order, positions and velocities in the world; made
  once, refilled by `simulation.particles(effect, emitter, cursor)`. **`ParticleWorld`** (heights and raycasts, with a
  reusable `Hit`) is taken by `CpuSimulation` and unused until collision (step 12).

### Step 4: GPU simulation (engine 0.140.0)

- The particle record is **80 bytes**: §2.3's field list adds up to 80, not the 64 its comment said (fixed above). It
  lives in `particles/particle.slang` (module `particle`), not `common.slang`: the constants generator names classes by
  the file's last segment, and `meshlets/common` already makes `CommonConstants`. Its layouts and constants are
  generated (`ParticleLayout`, `ParticleEmitterLayout`, `ParticleSpawnLayout`, `ParticleConstants`).
- The emitter table, chunk list, spawn ring and parameter blocks are `FrameBuffer`s rewritten each frame (every live
  instance's step and transform change every frame), not `RecordBuffer`s. One group per chunk; count and update run
  over every reserved chunk (idle ones exit at once), not indirectly over live ones. All passes on the graphics queue.
- Attributes over life multiply the size and color at birth, kept in the emitter's side range (two 16-byte units per
  particle) only where its program has an over-life module. A newborn already dead at the frame's end takes its slot
  for that frame, flagged `DEAD`.
- The pool does not grow; an emitter without room is counted (`Particles.unplaced()`). GPU bounds per instance are
  atomic min/max of ordered ints. Curves are not baked: 8 curves of 8 keys cost 0.007 ms more than of 2 keys at 1M
  particles (the reviewer note on E18 does not apply).

### Step 5: `ParticleDraw` (engine 0.141.0)

- A cull pass (each instance's GPU box against the frustum, writing its indirect draw) and a draw pass, one indirect
  draw per emitter renderer, emitters back to front by their effect's position. One pipeline per renderer kind and
  color format; facing, soft fade, flipbooks and alpha to add are branches on per-renderer values.
- The opaque depth is tested in the fragment shader (it samples it for the soft fade too), so particles get no early
  depth test. View-plane billboards face each eye's plane until the pairs of eyes of step 10; `FREE` faces the
  position until particles have normals.
- Renderer settings by name: `texture`, `stretch`, `soft`, `volume`, `flipbook.columns`, `flipbook.rows`,
  `flipbook.frames`, `flipbook.fps`, `flipbook.blend`. Textures are bindless slots given by `particles.texture(ref,
  slot)`; without one a renderer draws a round sprite.

### Step 6: CPU emitters on the GPU side (engine 0.142.0)

- CPU emitters take ranges like GPU ones and are listed flagged `EMITTER_CPU`; each frame their particles (as
  `CpuSimulation` left them, or as the game wrote them through `particles.writer(effect, emitter)`) are written to a
  per-frame buffer as world offsets from their effect's origin, whatever their space, and copied into their range by a
  compute pass ("particle upload"). Their box is computed on the CPU.

### Step 7: ribbons, trails and beams (engine 0.143.0)

- Ribbons join an emitter's particles in spawn order with the emitter's position as head; several strands (`ribbons`
  = k: particle i in strand i % k until step 19 writes ribbon ids) are ordered by a stable counting sort per emitter.
  Strided LOD (`stride`, `lodDistance`) for one strand. CPU emitters draw ribbons without a head and no trails.
- Trails keep each particle's last points in its side range, copied with the survivors; emitters with trails are
  capped at 65,536 particles. Beams go from the emitter to an end given per frame (`particles.beamEnd`; from step 8 a
  node through `EffectSync.beamEnd`) or up their `length`.
- Settings: `ribbons`, `stride`, `lodDistance`, `width.tail`, `width.head`, `tile`, `points`, `segments`, `noise`,
  `width`, `length`, `color.r/g/b/a`.

### Fixes from steps 4 to 7 (scene 0.47.0)

- `CpuSimulation` pulled world-space particles along their effect frame's down; forces act in the world, so gravity
  pulls world-space particles along world -Y and only local-space ones along world down turned into their frame, as
  the GPU does.
- `EffectRuntime.effects()` is public (the engine's `Particles` and `EffectSync` follow the runtime's effects).
- For step 8: world-space particles inherit `Effect.inheritVelocity` times their effect's velocity over the frame at
  birth (not burst calls'); `CpuSimulation` rebases the origin of world-space particles once its effect is
  `REBASE_DISTANCE` (4,096 m) away along an axis; `effects.replace(old, new)` restarts every instance of `old` with
  `new` keeping ids, nodes, seeds, scales and the parameters both have (`Effects.REPLACED`, `restarts(effect)` for
  followers; shared burst instances of `old` end).
- For step 9 (scene-animation): `Animator.node(Joint)`, and `Animators.animator(node)` finds the animator a listener
  heard of by its first node.

### Step 8: `EffectSync` and the effects plugin (engine 0.144.0)

- **`EffectSync`** (engine-scene) owns an `EffectRuntime` and a `CpuSimulation` of a scene's `Effects` and calls
  `Particles.update` each frame; viewers and quality go to the runtime. It does not take `SceneResources`: effects
  built in code need no loading, and renderer textures still go through `particles.texture(ref, slot)` (acquiring
  them through `SceneResources` is left for when effects load from files). Beams end at nodes through
  `sync.beamEnd(effect, emitter, node)`, followed every frame while both last (step 7's `particles.beamEnd` stays).
- **The plugin** is `EffectsPlugin` (key `effects`), in engine-scene beside `Assets`, not in engine-app, which has no
  render or scene dependencies, and not named `Effects`, beside scene-effects' store. One world's effects per plugin
  (`fx.effects(scene)`), synced in `afterUpdate` with the frame's delta; `effects.quality` and
  `effects.budgetMegabytes`; `fx.draw(sceneViews)` adds a `ParticleDraw` per view.
- **`Particles.update` may come between frames** (it is for the frame about to begin), as plugins call it.
- **Rebasing** has no pass of its own: once an effect is `Particles.REBASE_DISTANCE` (4,096 m, settable) from its
  origin along an axis, the origin moves to it and the emitter record carries the shift, which the update pass adds to
  the world-space particles and their trail points as it copies them. The emitter record is 176 bytes (with the shift
  and the inherited velocity). An effect 7,000 km out draws the same image as at the origin (0 channels off by more
  than 8 of 255, rebased every ten frames).
- **Inherited velocity**: world-space newborn get `inheritVelocity` times their effect's velocity over the frame,
  computed on the CPU in doubles; burst calls inherit nothing.
- **Pooling**: an ended effect's instances (their ranges) and its programs stay warm for `Particles.WARM_FRAMES`
  (300, settable) for the next instance of the same asset, at most 4 per asset, given back early when the pool lacks
  room. Module programs are kept that way rather than by a timer in `ModulePrograms`.
- **Hot reload**: an effect restarted by `effects.replace` keeps the ranges of emitters whose target, capacity and side
  units are unchanged (starting empty); the others are given back and placed again.
- **Transforms as drawn**: D91 is not built, so nodes' world transforms are taken as they are.
- 100 torches on rocks in the scene benchmark allocate 0 bytes per frame (median); the sync costs about 4 ms there
  (RX 9070 XT, 32 job threads), mostly `Particles.update` writing 300 emitter records and the CPU emitters' particles:
  to look at with budgets (step 18).

### Step 9: `EffectBindings` (engine 0.145.0)

- In engine-scene with the plugin (`fx.bindings(animators)`, evaluated before the sync). `onEvent(event, effect,
  joint)` serves every animator of its `Animators`: the listener's node finds the animator (`Animators.animator`), the
  effect plays attached to that animator's joint node, with `.scale(parameter[, factor])` (the event's weight),
  `.minimumWeight(w)` and `.offset(x, y, z)`; a joint beyond an animator's skeleton plays nothing there. Each effect
  played allocates its handle, as `Effects.play` does.
- `curve(animator, curve, effect, parameter).map(scale, bias)` sets the parameter every `update`, allocation-free, while
  the effect plays.
- The demo's fox carries four foot-down events (back left, back right, front left, front right) on its walk (0.5,
  0.692, 0.017, 0.375 s) and run (1.0, 1.092, 0.525, 0.417 s), found from the feet's heights, and a flickering curve on
  its survey that drives the torch's intensity.
- `CpuSimulation` kept the last shape's direction for a speed given before any shape (the GPU starts each particle
  with none); fixed in scene 0.48.0.

### CPU cost of the sync (engine 0.146.0)

- `Particles.update` wrote its tables (emitter records, spawn records, parameter blocks, chunk and ribbon lists) and
  the CPU emitters' particles field by field into `FrameBuffer`s, each write taking a lock to mark the other slots'
  pages: about 150 ns a write, 8.3 ms a frame for the benchmark's 100 torches once their embers were all alive. They
  are now written into native memory (`particles/Staging`) and copied into their frame buffers once a frame: 0.065 ms.
  The scene benchmark's "torches on 100 rocks" sync went from 4.1 to 1.5 ms over its first 20 frames (mostly the JIT)
  and a new "burning 2 s" scenario reads 0.36 ms (8.4 ms before); RX 9070 XT, 32 job threads.

### Step 10: sorting (engine 0.147.0)

- `ParticleSort` is a request of (particles, center camera, pool, state): equal requests are one sort, so both eyes
  of a pair, each drawn by `new ParticleDraw(particles, eyeView, head)` with the camera at their center, sort once and
  face the center (view-plane billboards face its position). The sort is by the **distance** from the center, not the
  depth along its ray: turning the head reorders nothing, as position-facing billboards turn with the position.
- Sorted emitters are those with a sorted billboard renderer (the default; `additive()` and `sorted(false)` are not),
  at most 4,096 a frame (`SORT_EMITTERS`; more draw unsorted), CPU emitters included. Keys are the emitter's place
  among them (top bits) above the top 20 bits of the squared distance; one global radix sort (4 bits a pass,
  FidelityFX-style count, scan and scatter with stable 1-bit splits in group memory) leaves each emitter's particles in
  a run, farthest first. Passes: as many as the keys' bits need, rounded up to even (6 up to 16 sorted emitters, 8 at
  most). No 16-bit keys for small emitters. Measured (one emitter, a view): 10k 0.091 ms, 100k 0.235 ms, 1M 1.725 ms.
- Draws: records carry the emitter, renderer, texture, sorted run, beam end and time; draws of one pipeline next to
  each other in the back-to-front order are one indirect multi-draw (`SV_DrawIndex`), so many emitters of one renderer
  kind cost one draw call (step 9's leftover).
- The demo's `StereoBench -Peffect` draws both eyes as a pair (one set of sort passes); it shows red and blue puffs.

### Step 11: forces, scene side (scene 0.49.0)

- Updaters (`Forces`): `force.wind` (a drag of `coupling` towards `Effects.wind(x, y, z)` times `scale`, the speed
  varying by `gusts` with value noise over the effect's time at 0.5 cells a second), `force.curl` (`strength`,
  `frequency`, `speed` of drift, 1 to 4 `octaves`), `force.vortex` (`strength` around, `pull` in, about an axis),
  `force.point` (`strength`, linear falloff to 0 at `radius`, `killRadius`, at `x`, `y`, `z`), `force.line`
  (through a point along an axis), `force.orbit` (`speed` in radians a second about an axis), `force.field` (a 3D
  texture asset, `strength`, a box of `sizeX` by `sizeY` by `sizeZ` around the emitter, `tile`; GPU only) and
  `limit.speed`. Axes and points are in the emitter's frame, about the emitter (its position at the step), +Y by
  default.
- Module fields gain a fourth kind, `ASSET` (an `AssetRef`); a module must give its asset fields ("needs its field").
- Integration: gravity, drag and wind exactly over a step (wind is a drag towards a velocity); forces depending on the
  position (curl, vortex, attractors, fields) taken as constant over a step from its start; orbits turn the position
  and velocity about their axis exactly (radius kept); the speed limit last; a particle within a kill radius gets
  `lifetime = age` (drawn no more, gone at the next step), as the GPU does.
- Curl noise is value noise on the integer lattice (`Hash` of the coordinates and seed, quintic interpolation, the
  analytic gradient) in three channels; its seed is `Hash.combine(emitter seed, salt + 8)`. It is sampled at the
  particle's coordinates (offsets from its effect's origin, or in its emitter's frame), so each effect has its own
  pattern, which moves when the origin rebases.

### Step 11: forces, engine side (engine 0.148.0)

- The kernel runs the forces as `CpuSimulation` does; the emitter record (192 bytes) carries the effect's time and the
  world's wind in the particles' frame. Asset fields encode the asset's sampled slot as the effect is encoded
  (`particles.texture(ref, slot)` must come first; a field without a slot does nothing). `VectorField` makes the 3D
  half-float texture of a `force.field` (bindless `volumes[]`, sampled with linear filtering, tiled or clamped).
- Every force matches the Java reference within 1 cm over 20 frames, in world and local space; orbits keep their
  radius on the GPU too. The demo has a swirl (a vortex pulling in, curl noise, rising).

### Step 12: collision, scene side (scene 0.50.0)

- `Collide` makes chains (`Collide.Collision`, added by `EmitterData.Builder.collide`): modules
  `collide.colliders` (GPU and CPU), `collide.height` (both), `collide.depth` (GPU), `collide.rays` (GPU, needs ray
  queries) and `collide.world` (CPU), each with `response` (bounce, slide, stick, kill, snap), `restitution`,
  `friction`, `radius` (0: half the particle's size) and `fallback` (1 for the methods after the first: used only
  where the ones before are not there). A chain is valid when one of its methods runs on the emitter's target; the
  others are left out there (the CPU takes the first CPU method of each chain).
- `EffectColliders` (`effects.colliders()`): planes (the node's XZ plane), spheres, boxes and capsules (along the
  node's Y) on nodes, in 24-bit layer masks; `Effects.advance` takes their nodes' world transforms, node scales do not
  apply, colliders of removed nodes go.
- A step collides after the forces, orbits and limits: the move from the step's start to its end, in the effect's
  world frame about its origin (local-space particles turned out and back), against the chain's method; the first hit
  responds: the particle is put at the contact (outside the surface by its radius), bounce turns the speed into the
  surface back times the restitution and keeps `1 - friction` of the speed along it, slide drops the speed into it,
  stick and snap freeze it there (flag `STUCK`: it only ages from then on), kill gives it `lifetime = age`. A hit sets
  `COLLIDED` for the step (collision events, step 14). Spheres, boxes and capsules push out what ends inside them (no
  sweep: fast particles can pass thin ones); planes and the height field count a move ending behind them that started
  no more than the radius behind; the world casts a ray along the move.

### Step 12: collision, engine side (engine 0.149.0)

- The kernel collides as `CpuSimulation` does, by the first method of each chain the GPU has (`ModulePrograms`
  encodes only it): the scene's colliders (up to 256 a frame, `MAX_COLLIDERS`), a height field and the collision
  view's last depth. `STUCK` particles only age; `COLLIDED` marks a hit for collision events.
- What the simulation collides with travels in a frame record (`ParticleFrame`) behind one push pointer, written as
  the frame records (frame buffers' addresses name the recording slot's copy, and `Particles.update` may come between
  frames): colliders, heights, depth, CPU uploads, events, ray tracing.
- Height field: `particles.heights(world, size, resolution)` samples the game's `ParticleWorld` on a grid around
  `heightFocus(x, z)` and samples it again when the focus moves a quarter of the size away (a storage buffer of floats,
  bilinear; no texture, no tiles yet). `EffectSync` given a world sets up 256 m at 2 m around its first viewer.
- Depth: `ParticleCollisionDepth`, added to the collision view, is a modifier of `SCENE_DEPTH` (a technique providing
  and modifying nothing is never built) that copies that view's depth on a grid of at most 512 samples a side with its
  view-projection and inverse into a buffer the next frame's simulation reads; the normal comes from neighbouring
  samples. Rain on a floor: stuck where the depth has the floor, through it without depth collision.

### Step 13: ray collision (engine 0.150.0)

- `simulate_rays.slang` is `simulate.slang` with `PARTICLE_RAYS` (an `#include`; the build compiles files that name
  `[shader(...)]`, so its comment does): `collide.rays` traces each particle's move plus its radius into the frame's
  top level (opaque, nearest hit), and two more rays beside it give the normal (against the move where they miss: no
  vertex fetch). `particles.rays(rayTracing, renderer)` turns it on where the device traces rays, for effects encoded
  after it; `ParticleSimulation` then requests the `TopLevel` and runs its update and spawn from the rays variant,
  ordered after the build. Elsewhere chains fall back (lavapipe runs the depth half of the test, as `ShadowsTest`).
- Deviation: no binning of chunks per variant; a frame with ray collision set up runs every emitter in the rays variant.

### Step 14: events and sub-emitters (scene 0.51.0, engine 0.151.0)

- Events: birth (a particle born in one frame sends it in the next), death (as it was), collision and periodic (after
  the step; periodic when its age crosses a multiple of the interval), each with a chance (`random(seed, salt + 15)`)
  and a count of children (any binding, at most 255); salts from `CpuSimulation.EVENT_SALTS` (8192), 16 per event; at
  most 8 events per emitter on the GPU. `emit` events (particles as emitters) are step 19 and do nothing yet.
- Children start at their parent (their shapes about it, turned by the emitter for world space), seeded
  `combine(combine(parent seed, salt), i)`, spawn indices counted per emitter, born at the frame's end;
  `Init.inherit(velocity, color, size)` takes a share of the parent's velocity and multiplies by its color and size.
  CPU emitters spawn into CPU emitters only (CPU into GPU later).
- GPU: the update appends event records (target, count, the parent's snapshot) into a buffer of 65,536 (more are
  dropped and counted, `particles.eventsDropped()`), keyed (parent slot, phase, event); a radix sort by that key, a
  rekey by (target, parent emitter), a second stable sort (the particle sort's passes, 8 each), then a scan gives each
  target its first event and children, which the spawn args add to its demand after its records and the spawn reads
  by binary search. The result matches `CpuSimulation` particle for particle (fireworks: a burst on the death of a
  burst on the death of a rocket) and is the same in two runs.
- E22 bursts: shared instances per asset and cell were built in steps 1 and 2; a thousand impacts in a frame are one
  instance and one draw. Not built: dropping a full shared instance's oldest particles for new demand (overflow is
  counted instead).
- The demo has fireworks.

### Step 15: mesh, bone and mask shapes (scene 0.53.0, engine 0.152.0)

- GPU-only initializers (`Shape`): `shape.mesh` (`mode`: `SURFACE`, `VERTICES`, `VOLUME`), `shape.bones` (`radius`)
  and `shape.mask` (`mask` texture, `channel` 0 to 3 or `LUMINANCE`, `threshold`, `sizeX`, `sizeZ`). A shape sets the
  position and the direction `init.speed` sends along: the surface's normal (interpolated vertex normals, the face's
  without), outwards from the box's center in the volume, away from the chain for bones.
- The engine names what each emitter emits from: `Particles.sources(provider)` is asked in every update for each
  emitter whose program has a mesh or bone shape, before its record is written, and answers through
  `particles.emitFrom(effect, emitter, poolMesh, data, worldRows)` and `particles.joints(effect, emitter, xyz, n)`
  (at most 16 joints). `EffectSync` is that provider: mesh shapes emit from the renderable of the node their effect
  follows as `sync.meshes(sceneSync)` draws it (`SceneSync.drawnMeshOf`: a skinned one's posed copy), bone shapes along
  `sync.joints(effect, emitter, nodes...)`. `particles.meshes(renderer)` gives the geometry pool; the simulation then
  requests `SceneData` and its spawn pass declares reads of the pool's buffers, so it runs after the scene upload and
  the deformation pass. The frame's sources (336 bytes each: the mesh into the emitter's frame as a 3 x 4 matrix, the
  alias tables' address, the joints in the emitter's frame) go behind `ParticleFrame.sources`; the emitter record's
  last word names its entry.
- Alias tables (`MeshTables`, Vose's method, deterministic) over the last level's triangles, built on the CPU from
  the mesh's data the first time an emitter emits from it: one by area, one by the volume of each triangle's
  tetrahedron to the box's center (so the volume is exact for meshes star-shaped about it). A copy uses its source's
  tables; positions and normals come from the copy as posed, dequantized with its posed box. Freed when the mesh leaves
  the pool. Vertices are drawn uniformly by index (no table).
- Deviation: masks thin instead of drawing by an alias table over texels. A masked particle is kept with the
  probability of the mask's channel at its texture coordinates (the mesh's UV0; else the position on the emitter's
  XZ plane across `sizeX` by `sizeZ`), or only where it reaches `threshold`; the others are born dead, so a mask
  lowers the count by its mean. No texel data is read on the CPU.
- An emitter whose mesh is not drawn yet (not loaded, no renderable) or whose chain has no joints gives born-dead
  particles rather than a heap at its origin.
- Tests: `MeshShapesTest` (4,000 particles on two quads of areas 1 and 3: 25 % on the small one within 3 %, the large
  one's six half-metre strips within 3 % of a sixth each; vertices; a cube's volume, half below its middle; a skinned
  copy emits where it is posed; bones uniform by length; a threshold mask and a density mask);
  `SceneSyncTest.particleMeshShapesEmitFromSkinnedRenderablesAsPosedAndBoneShapesAlongJoints` through `EffectSync`.
  The demo burns the fox (`DemoEffects.SKIN_FIRE`, embers over its posed skin as it walks).

### Step 16: mesh particles (engine 0.153.0)

- `GpuScene.reserveGpuRange(n)` claims the lowest run of `n` free ids (one range; `releaseGpuRange`): they start as
  holes, the CPU refuses to claim them (`set` throws, `remove` ignores them), the upload never scatters into them, and
  they count to `highWater()`. `RayTracing`'s instance pass gives them mask 0 and the empty bottom level, so the top
  level, skipped by `version()`, stands while particles move (checked where the device traces rays).
- `particles.meshes(renderer, instances)` reserves the range (`meshes(renderer)` alone: mesh shapes, no range) and
  notes `ParticleKeys.PARTICLES` as its writer (`GpuScene.writtenBy`). Emitters with a mesh renderer take a range of
  their capacity from it (none left: simulated, not drawn, `meshUnplaced()`). Renderer settings `orient` (0: tumbling
  about a random axis by how far the particle rolled, `rotation` advanced by speed over half the size in the update; 1:
  along the velocity; 2: as the emitter), `scale` (per metre of size) and `layers`. `particles.mesh(meshRef, poolMesh,
  materialBase)` registers what a renderer's `mesh` asset draws; unregistered, its particles draw nothing. The emitter
  record grew to 208 bytes (its range, mesh word with `INSTANCE_LIVE` and, from `castsShadows`, `INSTANCE_CASTS_SHADOWS`,
  first material, renderer word).
- "particle instances" (after "particle args") writes an `Instance` per slot of each mesh emitter's range from the half
  just written, holes past its live particles; ranges of ended (or warm) effects are cleared with a fill first.
- Deviation (the early group): no second group. With mesh particles set up, the whole simulation is built before
  culling: `MeshletCulling` and `ClassicLodGeometry` call `SceneData.written(g, renderer)`, which builds the producers
  of `GpuScene.writtenBy` keys first, so the simulation's passes (after the scene upload and deformation, which it
  requests) come before the culling's reads in the frame. All passes were on the graphics queue already (step 4), so
  "the main group still on the async queue" has nothing to check until step 18 moves it.
- Not built: previous transforms per slot (no motion vectors exist yet; with TAA, step 21); `EffectSync` resolving mesh
  renderers' assets through `SceneResources` (the game registers them with `particles.mesh`); tint and alpha per mesh
  particle (per-instance custom data).
- Tests: `MeshParticlesTest` (a quad particle drawn in the frame it is born, over five frames of two-phase occlusion,
  the same image by forward and visibility buffer shading within 4 pixels; shadow flags per emitter; the CPU's ids beside
  the range; holes after the effects end; the top level not rebuilt over frames of moving particles). The demo throws
  rocks every four seconds (`DemoEffects.DEBRIS`), tumbling, bouncing on the terrain, casting shadows.

### Step 17: lit particles (engine 0.154.0)

- Billboards only are lit (ribbons, trails and beams draw unlit); the lighting is the renderer's (`Renderer.lighting`)
  as drawn: `particles.quality(q)` (from `EffectSync.quality`) draws per-pixel per vertex below high and six-way maps
  per vertex at low. Deviation: the lowering is the engine's fixed rule, not a setting of the effect type.
- All read the view's light list (`Keys.LIGHT_LIST`, any binning, through `ILightList`) and its shadow inputs: the sun's
  cascades reach particles as surfaces, through `lights.slang`'s new `lightIrradiance` (the irradiance a light brings
  to a point after its shadow, which `lightSurface` now uses too). Without a light list, the sun of shading.slang. The
  sky's constant irradiance adds to all; no environment (indirect light) yet.
- Per vertex: a "particle lighting" compute pass per view lights each particle at its center (its cluster from its
  projected center), the same from every side, into four words per pool slot after the view's indirect draws (one
  buffer, one pointer: the draw push is full); the vertex shader reads it. Compute rather than the vertex shader because
  the graph has no access for sampling shadow maps in vertex shaders.
- Per pixel: per fragment from the normal map (`normal` setting, in the billboard's frame) or a sphere's normal across
  the quad, half-Lambert wrapped. Six-way: `texture` holds right, top, back and alpha, `texture.b` left, bottom, front
  and emission; each light weighs them by its direction's squared components in the billboard's frame.
- The draw record grew to 48 bytes (lighting, second texture, the light list's address); one pipeline per kind as
  before, the lighting a branch per draw.
- Cost (StereoBench, RX 9070 XT, 2064 x 2208 per eye, a column of smoke covering about a quarter of the view): "particle
  draw" per eye unlit 0.072 ms forward and 0.067 ms visibility buffer; per vertex 0.073 ms plus 0.005 ms of "particle
  lighting"; per pixel 0.164 ms forward and 0.155 ms visibility buffer.
- Tests: `LitParticlesTest` (smoke lit per pixel from one side, more than twice as bright there; per vertex the puff
  nearer the light; six-way maps lit only from their side; the sun without a light list; quality). The demo has lit
  smoke and `-Plights=N` scatters N point lights about the middle.

### Step 17b: particle lights (scene 0.54.0, engine 0.155.0)

- `Renderer.light(radius, intensity)` (kind `LIGHT`), settings `max` (per emitter, 64 by default) and `fraction` (the
  share of particles that light, by their seed). Intensity times the particle's color and alpha.
- `Lights.gpuLights(capacity)`: a device-local buffer of a count, three words and the lights, shared by both queues;
  `Lights.writtenBy(key)`. `ClusteredLights` builds the writers first, reads it in "light clusters", bins its lights
  after the scene's (`lightCount` plus the GPU's count, read on the GPU) and copies them into the light list after the
  slots (header `extra`, 128 bytes now), so shading reads one declared buffer; `handOut` gives them no shadows.
- `particles.lights(lights, budget)`: "particle lights count" (clears, counts each light emitter's candidates in 64
  buckets of a quarter octave of brightness), "particle lights threshold" (one thread: each emitter's threshold bucket
  for its budget, then the frame's over what the emitters keep), "particle lights write" (the kept become point lights,
  appended). At most 64 light emitters a frame. Nothing is read back.
- Deviation: the brightest are kept to a bucket: within the threshold bucket, which of them take the last places
  follows the atomics' order, so it may differ between runs (E11 holds for the particles, not for which of
  equally-bright lights win), and the lights' order in the buffer differs too (shading sums them in another order).
- Tests: `ParticleLightsTest` (a light over a floor lights it alike in forward and visibility buffer shading; 300
  candidates in two emitters over a budget of 40: the bright emitter's 5, the dim one's 35 brightest, none dimmer
  than 0.65 where the 35th of 200 uniform values is about 0.82). The demo's sparks light the ground and the rocks
  beside them (`DemoEffects.LIT_SPARKS`).

### Step 18: scalability (scene 0.56.0, engine 0.157.0)

- **Effect types enforced:** the levels of detail's drawn fraction and update rate reach the GPU (step 2 decided them,
  nothing used them); a GPU emitter's range is placed at its capacity times its type's spawn scale at the quality
  (at least a chunk; CPU emitters as authored, as they upload what they simulate).
- **Budgets in bytes:** `EffectRuntime.budget(bytes, cost)`; `EffectSync` gives it the pool's bytes and
  `Particles.bytes(effect)` (each emitter's range at its placed capacity, both halves, and side units). After the caps,
  the effects shown rank by their types' `priority` (higher first), then their significance; past
  `BUDGET_SOFT` (three quarters) of the budget they spawn at half their scale, past all of it they are culled as their
  type says. Placements of shared effects cost nothing, variants count.
- **LOD transitions:** `runtime.drawn(effect)` moves towards the level's drawn fraction over `FADE` (0.3 s of the
  world clock), decided at once on an effect's first update. The draws keep a particle whose hash (salt `0x4c0d`)
  is within the fraction and fade the next `0.08` of hashes out; the kept grow by `1 / sqrt(fraction)` (at most
  twice), keeping the cover. Levels keep step 2's hysteresis. An emitter updating every n frames is held in the
  others (`EMITTER_HELD`: its particles copied as they are, no events, no trail points) and moves by the time held
  (clamped to n times `MAX_STEP`) on the next; draws and sorts place its particles moved on by their velocity for
  the time held. Deviations: held per emitter, not per particle, so particles born while held age with the whole
  step at the next update (late by under n frames); held frames send no events (birth events wait for the update).
- **Half resolution:** a renderer's `half` 1, or every billboard at low quality. `ParticleDraw` draws those first:
  "particle half depth" (the farthest of each 2 x 2 of the scene's depth, the depth pyramid's program), "particle
  draw, half" into a cleared target of half the view's size, then "particle upsample" lays it over the scene, each
  pixel taking the nearest-depth texel of the four about it. Per eye, as every view draws its own. Deviations: half
  resolution particles are behind every full resolution one (composited first, not in the back-to-front order of
  emitters); the half pass ignores dynamic resolution's render region.
- **`effects.quality`:** low draws every billboard at half resolution, scales capacities as above and lowers lighting
  (step 17).
- **Async compute:** `new ParticleSimulation(particles).async(true)` marks every simulation pass async; the
  particles' buffers are shared by the graphics and compute families. Frames with mesh particles, mesh shapes or ray
  collision simulate on the graphics queue (their scene reads and writes order them about culling and the top
  level): no early and main groups. `ParticleBenchmark` (RX 9070 XT, a million particles, no opaque work to overlap):
  the frame's span 2.79 ms on the graphics queue and 2.60 ms async; the showcase (mesh particles) stays on graphics.
- **Debug views:** `ParticleDraw.overdraw(true)` draws a heat map (a dim orange a layer, about ten to white);
  statistics per emitter (alive, overflow) as before. Not built: bounds and emitter origins drawn as lines.
- **Hot spots fixed** (measured in the showcase, RX 9070 XT): "particle lights threshold" runs on a group of 256
  threads (a thread per light emitter, group-memory atomics for the frame's histogram): 0.189 to 0.010 ms; light
  emitters at most 1,024 (from 64); candidates rank by brightness times radius (a far-reaching light outranks a near
  one as bright; renderer setting `radius.size` scales a light's radius by its particle's size). Events of a frame
  (at most `EVENT_SMALL`, 2,048) sort in one group in group memory ("particle events small", 0.012 ms); the 48 radix
  passes are recorded only after a frame (from the statistics, 60 frames back) sent over 1,024, else further events
  are dropped and counted for those frames. The view sort keeps 4 keys a thread (1,024 a group) and ranks each digit
  with wave ballots (any wave of 4 to 128 lanes), writing keys straight to their places: a million 1.84 to 0.77 ms.
  A dust play allocated its handle: `Effects.start` plays by id, and `EffectBindings.onEvent(...).burst()` bursts
  into the shared instance of the cell (E22): the showcase's hundred foxes' footsteps are a few instances, not a
  thousand emitters, and the sync's 1 % high went from 1.33 to 0.35 ms. `EffectData.equals` checks identity first.
- **`ParticleBenchmark`** (RX 9070 XT, 2026-11-02; before is engine 0.156.0):

  | | before | after |
  |---|---|---|
  | a million particles: update | 0.280 ms | 0.278 ms |
  | the simulation's other passes | 0.528 ms | 0.528 ms |
  | the view's sort | 1.668 ms | 0.729 ms |
  | draw (unlit sparks, 1920 x 1080) | 1.367 ms | 1.392 ms |
  | overdraw: 600 lit smoke puffs of 3 m, 2064 x 2208, full | 3.9 to 4.8 ms | 4.0 to 4.6 ms |
  | the same at half resolution | | 1.34 to 1.47 ms |
  | 1920 x 1080, full / half | 1.29 to 1.60 ms | 1.75 / 0.50 ms |

  (Overdraw runs vary by about 20 % between runs.)
- Tests: `ParticleDrawTest` (levels of detail drawing other, larger particles; half resolution as full within a
  twentieth of the pixels, behind a nearer wall's edge; the overdraw heat map adding a layer at a time),
  `ParticlesTest` (held emitters every other frame, never more than a frame behind; the async queue against the
  reference), scene's `SharingTest` (budgets by priority, fades, plays by id), `ParticleSortTest`,
  `ParticleLightsTest`, `EffectBindingsTest` (bursts).

### Step 18a: shared simulations (scene 0.56.0, engine 0.157.0)

- **`EffectData.Builder.shared(variants, nearDistance)`.** Eligibility from the modules: every emitter on the GPU in
  local space, no collision, mesh or bone shapes, no mesh, light or beam renderers; otherwise the asset is simulated
  per instance and `sharingRefusal()` says which emitter and why. Lights are per instance: the showcase's torch is a
  shared fire and an unshared light effect.
- **`Effects`:** playing a shared asset makes a placement (`placement(id)`), drawing variant `played % K` in turn
  (`variantOf(id)`), started on demand at the origin, unrotated, seeded `Hash.combine(data.hashCode(), v)`. A variant
  with no placement left stops (its particles live out, undrawn); a new placement then starts a new one. Placements
  refuse parameters of their own and inherited velocity (`IllegalStateException`); they move with their nodes or
  `moveTo` (the decision: placements may move, rigidly: right for local-space content), and `Effect.scale(s)` scales
  one. Deviations: variants start together, de-phased by their seeds only (no time offsets, no variant picked by
  phase); `effects.replace` ends placements and variants.
- **Runtime:** placements take significance, caps and levels like any effect but spawn nothing; a variant is as near
  as its nearest placement shown (culled when none is). Within `nearDistance` (with the type's hysteresis) a
  placement gets a simulation of its own (`nearOf(id)`, an effect at its place with its seed) and its share of the
  variant (`placementWeight`) fades to 0 over the asset's lingering; going far, its own stops and the variant fades
  back in. A stopped placement fades out over its lingering, then ends.
- **Engine:** placements have no instances; after the simulated emitters, `Particles` lists a record per placement
  and variant emitter (`EMITTER_PLACEMENT`): the variant's record at the placement's origin, rotation and scale, its
  fade and drawn fraction; draws, culling and the sort read them like any (the sort's room grows with them), the
  simulation never does. Variants' own records are `EMITTER_HIDDEN`. Placements lit per vertex draw lit per pixel (a
  slot's light is its variant's).
- Measured: 1,000 torches in the showcase (`-Ptorches=1000`) are 6 simulations of 4 emitters (about 12,000 particles
  alive in all) and 4,000 drawn records.
- Tests: `SharingTest` (eligibility and refusals; variants in turn; placements ending, outliving their variant;
  near placements' own simulations and fades; budgets), `ParticlesTest.sharedEffectsSimulateTheirVariantsOnly`
  (40 placements, 3 variants), `ParticleDrawTest.placementsDrawTheirVariantsWhereTheyAreMovedScaledAndStoppedAlone`.

### Step 19: emitter state on the GPU (scene 0.56.0, engine 0.157.0)

- **`ParticleEvent.emit(target, perSecond)`:** after each step a parent adds `perSecond x step` to an accumulator in
  16.16 fixed point (four a particle, `MAX_EMITS`, in a side unit after those over life: `PROGRAM_EMIT`), and sends
  the whole children it owes (at most 255). Its children are spread back over the parent's step along its path, the
  youngest last (age and position by the parent's velocity), seeded by their spawn index (an emitting parent's events
  come every frame, alike). `CpuSimulation` does the same in Java; GPU and CPU match particle for particle.
- **Ribbons per parent:** a ribbon renderer's `ribbons` 0. A particle's ribbon id is in `custom.y`: its spawn index
  for emitters' own (strands as spawn index % ribbons), its parent's for children. Such emitters' particles are keyed
  (the emitter's place, the low 20 bits of the id) and sorted by the view sort's radix passes, stably, into the ribbon
  order ("particle ribbon keys", "particle ribbon sort", "particle ribbon order"); draws taper each ribbon by age.
- Deviations: no child ranges per parent (children share their emitter's range, ordered by the sort); emitting starts
  in the frame after a parent's birth; timing by the parent's age only through the rate's binding.
- The showcase's rockets emit their trails (40 a second) instead of periodic events; `-Psparks=100000` throws
  100,000 sparks each with a trail of its own (24 points a second, 0.18 s): about 440,000 particles.
- Tests: `ParticlesTest.particlesAsEmittersMatchTheReferenceAndTheirRates` (15 children by half a second at 30 a
  second, against the reference), `ribbonsPerParentKeepEachSparksTrailTogetherInSpawnOrder`.

### Step 20: plugin modules (engine 0.157.0)

- **`ParticleModules(shader, types)`**, given to `particles.modules(...)`: the game's module types (scene-effects'
  `ModuleType`, at most 8 float fields) and its kernel variant, a Slang file defining `PARTICLE_MODULES` and a
  `ParticleModules` type dispatching `IParticleModule.init` and `update` by index, then including
  particles/simulate.slang. Module `i` is opcode `OP_PLUGIN + i`; it gets its fields evaluated (`ParticleModuleInput`,
  with the step, time, salt, frame's down and wind); initializers run in their place, updaters after the built-in
  motion. Effects naming them are built against `modules.registry()`. Deviations: no `[ParticleModule]` attribute and
  generated dispatch (the generators live in build-logic); the variant replaces the rays variant (no ray collision
  with plugin modules); no Java twins (a CPU emitter naming a plugin module is refused by `CpuSimulation`). All three
  done since, by E24 (below).
- **`modify(PARTICLES)`:** a technique modifying `ParticleKeys.PARTICLES` runs after the simulation and before
  anything reading them (tested).
- **Built-in modules** the showcase uses: `Init.rotation` (and `randomRotation()`), `Init.frame` (where a flipbook
  starts, a fraction of its frames; `randomFrame()`), and `Shape.mesh(mode, upward)` keeping a particle with the
  chance `1 - upward + upward max(0, n.y)`. Rotation and frame have Java twins (`ParticleCursor.rotation()`,
  `frame()`); the mesh weighting is GPU only. The showcase's flames start at random frames and its fox fire weights
  its skin's upward faces (the texture mask is no longer needed).
- Tests: `ParticleModulesTest` (a force and an initializer of the test's, test/particlegust.slang; a technique fading
  a particle), `SharingTest.rotationsAndFlipbookStartsAreDrawnPerParticleOnTheCpu`.

### Step 21: glass order and TAA hooks (engine 0.157.0)

- **Glass order** per renderer (open question 1): `glass` 0 after (the default), 1 before: drawn by
  `ParticleDraw.beforeGlass(particles, view, center)`, a technique at `TRANSPARENT` before `Transparency.ID`, so
  blended glass tints them and `SceneBehind` holds them; 2 split, at a view's `ParticleKeys.GLASS_DEPTH` (the nearest
  blended surface, which a transparency technique may provide): fragments behind it drawn before the glass, the rest
  after. No transparency technique writes that depth yet, so split renderers draw after the glass. `EffectsPlugin.draw`
  adds both techniques per view.
- **TAA hooks**, as TAA does not exist: `ParticleKeys.REACTIVE`, an R8 image of the particles' coverage (alpha and
  added light), drawn only where a technique requires it; `ParticleKeys.PREVIOUS_INSTANCES`, per slot of the mesh
  particles' instance range, the instance a frame before (moved back by its velocity, tumbling turned back by its
  roll), written by "particle instances".
- Tests: `ParticleDrawTest.particlesBeforeGlassAreTintedByItAndThoseAfterItAreNot`, `theReactiveMaskCoversWhere
  ParticlesAre`. The showcase's `-Pglass=true` puts a window before the bonfire, its fire drawn before glass and
  sparks before the window after it.

### Particle modules from Slang (E24; build-logic 0.15.0, scene 0.57.0, engine 0.158.0)

- **Generator:** truesim.slang collects `[ParticleModule]` structs (the project's and its libraries', through
  `particle-modules.index`) into `particle-modules/dispatch.slang`, included by particles/simulate.slang, and the
  `slang.particleModules` Java class (`OWN`, its module types; `MODULES`, a `ParticleModules` of every module by
  opcode). Templates: engine-render's `particle-modules` (`modules.properties`, `dispatch.slang.template`,
  `Modules.java.template`, `entry.slang.template`, `entryRays.slang.template`).
- **Built-in modules** as structs in particles/modules (shapes, birth, forces, collide, rays, overlife); the kernel's
  hand-written switches are gone. An op word carries its module's stages (`op | words << 8 | stages << 16`), so a step
  loops only over the stages its program has. `OP_*` and `PLUGIN_FIELDS` left particle.slang (and
  `ParticleConstants`); the generated `OP_<NAME>` constants replace them (`OP_INIT_INHERIT` in `spawnChild`).
- **Games:** `IParticleModule`, `ParticleModuleInput`, `PARTICLE_MODULES` and the hand-written `ParticleModules` record
  are gone; a game's variants now include ray collision (step 20's variant replaced the rays variant).
- **scene-effects:** `ModuleTwin`, `TwinParticle`, `ModuleType.twin` (`Builder.twin`), `EffectData.modules()` (the
  registry an effect was built against, which `CpuSimulation` resolves twins from).
- **Measured** (`ParticleBenchmark`, RX 9070 XT, ms, before / after, two runs each): gravity and drag update
  0.275, 0.274 / 0.274, 0.273; with 8 curves of 8 keys 0.464, 0.465 / 0.459, 0.458; a million on the graphics queue,
  update 0.279, 0.278 / 0.279, 0.278 and simulation 0.528, 0.528 / 0.528, 0.528; async simulation 0.565, 0.563 /
  0.561, 0.564. Within noise.
- Still by hand: a built-in module's mirror in `ModuleRegistry.BUILT_IN` and its twin in `CpuSimulation`; what
  `spawnChild` reads of `init.inherit`; the name checks of `shape.mesh` and `shape.bones` (emission sources) and of
  `collide.*` (chains) in the encoders.

### Folded in from steps 14 and 15

- `EffectsPlugin`: `collisionView(view)` (a `ParticleCollisionDepth`), `meshes(sceneSync, renderer, instances)`,
  `lights(lights, budget)`, `rays(rayTracing, renderer)`, and `draw(views)` before and after glass.

### The showcase (engine 0.156.0, updated in 0.157.0)

- `./gradlew :engine-demo:showcase -Pcrowd=<Fox.gltf>`: 100 foxes in an arena at dusk with everything at once: a
  layered bonfire and torches (core, flame tongues from a flipbook, embers, lit smoke, a flickering light), a tenth
  of the foxes on fire over their posed skins, dust at every footstep (bursts into shared instances), fireworks waves
  (rockets emitting trails, bursting into stars and sparkles, flashes lighting the ground), blasts of tumbling,
  shadow-casting rocks, hail, sparks, swirls, a dust devil (half resolution), smoke in gusting wind, orbs with
  ribbons and beams. Frames go to PNG files (`-Pout`, `-Pframes`, `-Pfirst`, `-Pevery`, `-Pstills`, `-Psize`,
  `-Pcamera=orbit|keys:...|follow:...`), or `--args=measure` times the frames in stereo at a Quest 3's eye size.
- Options: `-Ptorches=N` (torches in rings beyond the arena, all placements of six shared fires), `-Psparks=N`
  (sparks with trails), `-Pglass=true` (a window), `-Pasync=true` (the simulation async where it may be).
- Its fires belong to the effect type `FIRES` (levels at 22 and 40 m from the camera: drawn 0.6 and 0.35, the far one
  updated every second frame); the torches are shared (6 variants, own simulations within 9 m); the bonfire's smoke
  and the dust devil draw at half resolution.
- `--args=measure` (Ryzen 9 9950X3D, RX 9070 XT, stereo 2064 x 2208 per eye, 600 frames; ms per frame, mean / 1 %
  high / worst), engine 0.156.0 against 0.157.0:

  | | before | after |
  |---|---|---|
  | wall clock, frame start to next start | 2.817 / 4.170 / 5.198 | 2.213 / 3.026 / 4.263 |
  | CPU, main thread | 2.234 / 3.616 / 4.326 | 1.321 / 1.919 / 2.736 |
  | scene update | 0.243 / 0.415 / 0.448 | 0.232 / 0.386 / 0.462 |
  | animation | 0.202 / 0.303 / 0.348 | 0.199 / 0.321 / 0.432 |
  | effects sync | 0.347 / 1.331 / 1.842 | 0.127 / 0.371 / 0.467 |
  | graph record and submit | 1.442 / 1.890 / 2.832 | 0.763 / 1.090 / 2.027 |
  | waiting for a frame slot | 0.573 / 1.182 / 1.352 | 0.882 / 1.591 / 3.128 |
  | GPU, frame | 2.547 / 3.067 / 3.231 | 2.200 / 2.748 / 4.294 |
  | bound by (frames, GPU / CPU) | 193 / 407 | 523 / 77 |
  | 1 % low | 240 fps | 331 fps |

  Per eye, "particle draw" 0.229 ms before; after 0.121 ms plus the half resolution's 0.010 (depth), 0.031 (draw)
  and 0.028 (upsample). "particle lights threshold" 0.189 to 0.010 ms; the 48 event sort passes (about 0.1 ms) are one
  of 0.010 ms, and recording them went with them (most of the record's gain). The frame is now bound by the GPU.
