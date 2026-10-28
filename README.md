# scene

The true-sim scene: the data that describes a world, shared by the engine, dedicated servers, tools and mods. It says
**what** there is (nodes, transforms, meshes, materials, lights, cameras, which parts of the world are loaded),
never **how** it is drawn. GPU upload, culling, LOD selection and shaders stay in the engine, so mods and servers use
the scene without depending on it.

One model covers every kind of game:

- a 2D game: an orthographic camera with sprite and tilemap renderables;
- a tile-based or top-down game;
- a fixed-size 3D level: a world with one cell, loaded whole;
- an open world: streamed cells, multi-level detail out to the horizon;
- multiplayer: a server streams only physics data around every player, without rendering.

## Modules

One repository; each module is published on its own, so a program takes only what it needs. A dedicated server, for
example, takes core, world and collision without any render data.

| Module            | Contents                                                                                  | Status      |
|-------------------|-------------------------------------------------------------------------------------------|-------------|
| `scene-core`      | node handles, the transform hierarchy, bounds, layers, change tracking, spatial queries   | milestone 1 |
| `scene-world`     | worlds and instances, cells, data layers, interest sources, multi-level streaming         | milestone 2 |
| `scene-collision` | each cell's colliders and terrain heights, on common-collision shapes                    | milestone 3 |
| `scene-assets`    | asset references and the cache; meshes, materials, textures, animation, sprites, tilesets  | milestone 4 |
| `scene-render`    | renderables, cameras, lights, environment, sort layers                                    | milestone 5 |

Importers (glTF and others), a serialization format and networking come later, in their own repositories.

## Design

### Performance and usability

- **Hot paths allocate nothing.** Scene data lives in structure-of-arrays storage, nodes are `long` handles
  ([`NodeHandle`](core/src/main/java/com/truesim/scene/core/NodeHandle.java)), and per-frame updates touch primitives.
- **Per-frame cost follows what moves.** `update()` touches only moved, created or reparented nodes and their
  descendants; bounds are not pushed up the hierarchy. Nodes marked `Mobility.STATIC` (terrain, buildings) are
  indexed once per cell, only `DYNAMIC` ones are refit each frame.
- **Consumers see changes, not the whole scene.** Changes come out as change logs (common-state), so the engine
  uploads what changed and replication sends what changed.
- **Usable at the edges.** Typed keys instead of strings, builders for setup, and value types (common-math's `Vec3`,
  `Transform`) where convenience matters more than the last allocation. Primitive overloads cover the hot paths.
- **Deterministic.** The same inputs give the same scene on every machine and thread count, as in common-jobs and
  common-collision.

### Measured

`SceneBenchmark` (run with `TRUESIM_BENCHMARK=true`): 1,000,000 static and 10,000 moving nodes, on a desktop CPU.

| Per frame                                   | Time     |
|---------------------------------------------|----------|
| nothing moved: scene and index update       | 0.002 ms |
| 10,000 moved: scene update                  | 0.6 ms   |
| 10,000 moved: spatial index update (refit)  | 0.4 ms   |
| frustum query, about 20,000 visible         | 0.66 ms  |

The frustum query uses plane masks and accepts fully visible subtrees untested (common-math 0.4.0); per-cell static
hierarchies come with milestone 2.

### Streaming, measured

`StreamingBenchmark` (`TRUESIM_BENCHMARK=true`): 4,096 cells of 64 static props (262,144 nodes), generated in memory,
so it measures the world, scene and index rather than a disk.

| Step                                       | Nodes created on attach     | Batches prepared in the background |
|--------------------------------------------|-----------------------------|------------------------------------|
| request a new cell (starts its load)       | 0.2-1.4 µs, see below       | 0.2-1.4 µs                         |
| attach a loaded cell (64 nodes)            | 18 µs                       | 12 µs                              |
| request and release an already loaded cell | 16 ns                       | 16 ns                              |
| 64 cells streaming into the loaded world   | 0.4 ms attach, 0.8 ms index | 0.3 ms attach, 0.1 ms index        |
| 64 cells leaving it                        | 0.1 ms                      | 0.1 ms                             |
| all 4,096 cells in one go: worst frame     | 191 ms                      | 25 ms                              |

Static nodes are indexed per cell: each streamed cell builds a small tree of its own, under a top-level tree of
cells, so a cell coming or going costs about its own size. Before that, any static change rebuilt one tree over
everything (154 ms at this size). A per-frame attach budget (#7) spreads bursts over frames.

Requesting a new cell (`RequestBenchmark`, after warm-up) costs 0.2 µs while its load waits in a full lane, and
1.2-1.4 µs while other loads finish meanwhile, as they contend for the lane's lock: about 5 ms on the main thread for
a teleport into 4,096 new cells. Earlier figures of 25-40 µs were JIT warm-up and the benchmark's loaders competing
for the CPU. A lock-free lane queue could keep requests near 0.2 µs (true-sim/common-jobs#5).

### Parallel work on a job system

Four steps can run on a common-jobs `JobSystem`, with the same results as on one thread:

```java
JobSystem jobs = JobSystem.ofAvailableProcessors();
scene.setJobs(jobs);   // moved subtrees compute their world transforms and bounds in parallel
index.setJobs(jobs);   // changed static groups rebuild their trees in parallel
world.setJobs(jobs);   // moved interest sources select their cells in parallel

index.queryFrustums(views, 5, VISIBLE.mask(), results); // a camera and four shadow cascades at once
```

Each parallel part writes only its own data: a subtree its nodes, a group its tree and box, a source its wanted sets, a
view its results. Merging happens in a fixed order: the scene records changes in the same order as on one thread, group
trees take their top-level slots in order, and the world applies each source's requests and releases in the order the
sources were added, so results do not depend on the number of threads. Call `update()` and `queryFrustums` from the
thread that submits to the job system. The serial parts limit the gain where they dominate, e.g. recording changes.

| Measured (32 threads)                                     | One thread | Job system |
|-----------------------------------------------------------|------------|------------|
| `SelectionBenchmark`: 512 players moving, `World.update()` | 53 ms      | 21 ms      |
| index update, all 4,096 cells in one frame (nodes)        | 96 ms      | 17 ms      |
| index update, 64 cells into the loaded world (nodes)      | 0.8 ms     | 0.3 ms     |
| `SceneBenchmark`: 5 views at once, 102,000 nodes found     | 3.1 ms     | 1.3 ms     |
| `SceneBenchmark`: first scene update, 1,010,000 nodes      | 58 ms      | 35 ms      |
| `SceneBenchmark`: scene update, 10,000 moving nodes        | 0.54 ms    | 0.30 ms    |

### Staged content: prepare in the background, apply cheaply

A loader can build a cell's nodes as a `SceneBatch` on its lane: hierarchy, transforms, bounds, mobility and layers,
as arrays. `prepare()`, still in the background, computes world transforms and bounds and builds the tree of the
static nodes. On the main thread `content.apply(batch)` copies the arrays into the scene and hands the tree to the
spatial index, which adopts it instead of building one, so attaching is mostly copying memory:

```java
// CellLoader.load(), on a lane:
SceneBatch batch = new SceneBatch();
for (Prop prop : props) {
    int node = batch.add();               // or batch.add(parent): parents first
    batch.setTranslation(node, prop.x(), prop.y(), prop.z());
    batch.setLocalBounds(node, -1, 0, -1, 1, 4, 1);
    batch.setMobility(node, Mobility.STATIC);
}
batch.prepare();

// CellLoader.attach(), on the main thread:
content.apply(batch);
```

Nodes from a batch are ordinary nodes: they report `CREATED` through the change log, and moving one later rebuilds
its cell's tree as usual. If anything touches them before the index catches up, or an index misses the hand-over, the
index builds the tree itself.

### Fly-through, measured

`FlyThroughBenchmark` (`TRUESIM_BENCHMARK=true`): a camera flies over a 16 km x 16 km world at 300 m/s for 10 s,
paced at 60 fps, streaming terrain by screen-space error (2 px, coarser tiles 1 % off) to an 8 km horizon, props by
distance bands with HLOD proxies, and collision within 200 m. Loaders sleep 2-5 ms per cell like a disk; the attach
budget is 32 cells per frame; it is measured after a warm-up flight elsewhere, as a game runs after its first minute.

| Per frame, main thread                      | median  | p99     |
|---------------------------------------------|---------|---------|
| all of it (update, attach, scene, index, query) | 0.59 ms | 9.4 ms |
| world update (selection, requests, releases) | 0.37 ms | 0.79 ms |
| attaching                                    | 0.01 ms | 0.07 ms |
| spatial index update                        | 0.07 ms | 1.9 ms  |
| frustum query (about 1,000 visible)          | 0.12 ms | 0.41 ms |

About 2,900 cells are resident; 640 load per second in flight, and on average 11 of the camera's cells are still
loading in any frame (42 at most), so streaming keeps up. A few frames still take 10-50 ms in a single phase, which
none of the phases shows at p99; without the warm-up, the first second of flight has hitches of up to 360 ms while
the JIT compiles.

### Positions

World positions are `double`, like common-math and common-collision: precise to about a nanometre even 10,000 km from
the origin, so physics, servers and streaming need no origin shifting. Cells are a streaming concept (which cell a
position falls in), not part of positions. The engine converts to camera-relative floats for the GPU; a network
transport can quantize.

### Streaming: layers, levels and interest sources

The world is split into cells. Each kind of data is a **layer** with its own resolutions; each **interest source**
says how much of which layers it needs, and the world merges all sources:

```java
// Data: which layers exist, at which resolutions (typed keys, no strings at use sites).
static final Layer<TerrainTile> TERRAIN =
    Layer.of("terrain", TerrainTile.class, Levels.grid(64, 6));   // 64 m tiles, 6 levels, each 2x coarser
static final Layer<PropCell> PROPS = Layer.of("props", PropCell.class, Levels.hlod(32, 4));
static final Layer<CollisionCell> COLLISION = Layer.of("collision", CollisionCell.class, Levels.single(128));

// Interest: reusable profiles saying what a kind of observer needs.
static final InterestProfile PLAYER_VIEW = InterestProfile.builder()
    .layer(TERRAIN, Detail.screenError(2), Range.radius(20_000))  // as fine as 2 px allow, to the horizon
    .layer(PROPS, Detail.bands(150, 600, 2500))
    .layer(COLLISION, Detail.full(), Range.radius(200))
    .build();
static final InterestProfile SERVER_PLAYER = InterestProfile.builder()
    .layer(COLLISION, Detail.full(), Range.radius(300))     // physics only
    .build();

// Sources: one per camera, viewport or player; cheap and mutable.
InterestSource camera = world.addInterest(PLAYER_VIEW, Priority.HIGH);
camera.setView(fovY, viewportHeight);   // for screen-space error: zooming in loads finer detail
camera.moveTo(x, y, z);             // every frame, allocation-free
world.update();                     // requests what sources newly need, releases what none needs

// Loaders: load in the background, attach on the main thread at the frame boundary.
world.register(PROPS, propLoader);
tasks.drainCompleted();             // once per frame: finished loads attach here
```

- **Levels per layer:** each layer has its own cell sizes; within a layer each level is a whole multiple of the one
  below, doubling by default (`Levels.grid(64, 6)`) or in steps of your choice (`Levels.sizes(64, 256, 4096, 16384)`,
  e.g. ending in a very low-poly horizon).
- **Several kinds of detail:** LOD chains and mips inside assets, HLOD proxies for groups of cells (house → block →
  district → horizon), and per-layer resolutions such as a terrain pyramid out to the horizon.
- **Policies per layer:** always resident (full loading), distance bands, or screen-space error.
- **Budgets and order:** loads queue on the lane by source priority, coarse levels first; `setAttachBudget` caps
  how many loaded cells attach per frame, so a burst (a teleport) spreads over frames, best first.
- **No holes:** a source holds the cells it no longer wants until the cells replacing them in their area have
  loaded, coarse to fine and back.
- **Hysteresis:** a cell switches to finer detail at the band distance, but back to coarser only 15 % further out
  (`World.setHysteresis`), so a camera hovering at a boundary does not thrash loads.
- **Ready:** `world.isReady(source)` says when everything a source wants has loaded, e.g. to end a loading screen.
- **Budgets** for memory and I/O per layer; source priority and visible error decide the order.
- **Merging:** each cell and layer gets the finest detail any source asks for, loaded once however many sources need
  it.
- **The scene sees only resident content:** loaders turn cell data into nodes, proxies and colliders, which arrive
  through the change logs at the frame boundary.

### Features and variants

One world can have several versions, e.g. a racetrack's layouts or event scenarios: the same cells and shared content
(terrain, grandstands), with the parts that depend on **features**. A feature is on or off and carries a parameter; a
**variant** is a named set of feature states, and can build on another one:

```java
static final Feature<Chicane> CHICANE = Feature.of("chicane", Chicane.class, Chicane.TIGHT);
static final Feature<Void> NIGHT_LIGHTS = Feature.toggle("night-lights");
static final Feature<Double> FLOODLIGHTS = Feature.live("floodlights", Double.class, 1.0);

static final Variant GRAND_PRIX = Variant.builder("grand-prix").enable(CHICANE, Chicane.WIDE).build();
static final Variant NIGHT_RACE = Variant.builder("night-race").basedOn(GRAND_PRIX).enable(NIGHT_LIGHTS).build();

// Loaders read features while loading: what they read is what the cell depends on.
public PropCell load(Cell cell, FeatureView features) {
    PropCell props = PropCell.read(archive, cell);
    if (!features.isOn(NIGHT_LIGHTS)) {
        props.removeTagged("night-lights");               // content tagged for a feature
    }
    props.useChicane(features.value(CHICANE));           // or a parameter choosing assets
    return props;
}

world.features().select(NIGHT_RACE);
world.features().disable(NIGHT_LIGHTS);                  // single features on top of the variant
world.features().listen(FLOODLIGHTS, (feature, on, intensity) -> lights.setIntensity(intensity));
world.update();                                          // applies the changes
```

- **Exact reloads:** the world records which features each cell's load read; a change reloads exactly those cells.
  Cells that read nothing, like the shared terrain, stay as they are.
- **No gaps:** a reloading cell keeps its content until the new data has loaded, then swaps in the same frame, within
  the attach budget. Loads that read a feature changed while they ran load again; a failed reload keeps the old
  content.
- **Live features** apply without reloading: their changes reach listeners instead.
- **Small and immutable states:** loads in the background read a consistent snapshot, and a server can later
  replicate the selected variant and its changes.

### Collision

A collision layer streams each cell's static colliders and terrain into a common-collision `CollisionWorld`, with
or without rendering. Loaders build a `CellColliders` in the background; `ColliderLoader` adds its colliders when the
cell attaches and removes them when it goes:

```java
static final Layer<CellColliders> COLLISION = Layer.of("collision", CellColliders.class, Levels.single(128));

CollisionWorld collisions = new CollisionWorld();
world.register(COLLISION, new ColliderLoader(collisions, (cell, features) -> CellColliders.builder()
    .terrain(readHeights(cell), cell.minX(), 0, cell.minZ())             // a heightfield per cell
    .add(barrierHull, barrierPose, CONCRETE, STATIC_GROUP, -1, false)      // material and filter
    .add(trackMesh, Transform.IDENTITY, ASPHALT, STATIC_GROUP, -1, true)    // baked into the static tree
    .build()));

// A physics-only server: one source per player, wanting only collision.
InterestSource player = world.addInterest(InterestProfile.builder()
    .layer(COLLISION, Detail.full(), Range.radius(300)).build(), Priority.HIGH);

// Every step, after the frame boundary (tasks.drainCompleted()):
collisions.update();

TerrainHeights ground = new TerrainHeights(world, COLLISION);
double y = ground.heightAt(x, z);                                         // NaN where no terrain has loaded
int material = ground.materialAt(x, z);                                   // the ground type, e.g. for footsteps
```

- **Features apply:** a loader reading features (barriers on or off, a layout) reloads its cells on a change, and
  their colliders swap like any other content.
- **Materials:** each collider has its own material, and meshes and heightfields one per triangle or cell; contacts
  and hits report them, so physics needs nothing more. `terrain(heightfield, x, y, z, material, group, mask)` also
  gives the terrain collider a filter, e.g. for queries that skip terrain.
- **Heights match collision:** `heightAt` uses the finest loaded level and the same two triangles per heightfield
  cell as common-collision, so it is where a ray straight down hits the terrain.
- **Levels:** coarser collision levels (a whole valley as one low-resolution heightfield) cover what finer cells do
  not; each loaded cell adds its colliders, so overlapping levels both collide while a swap is under way.

### Assets: references, sources and the cache

Nodes, cells and other assets point at assets with typed references, by stable id (assigned at import, kept when an
asset is renamed or moved) or by procedural recipe. One `AssetCache` per process owns the loaded data, so every asset
loads once however many use it:

```java
static final AssetType<MeshData> MESH = AssetType.of("mesh", MeshData.class, MeshData::byteSize);

AssetCache assets = new AssetCache(tasks);
assets.setSource(AssetSource.firstOf(                // where bytes come from, tried in order
    AssetSource.directory(patches),
    AssetSource.directory(baseGame).through(AssetSource.GZIP),
    cdn.through((id, resource, in) -> decrypt(in, keyFor(id)))));
assets.register(MESH, GLTF_MESHES);              // how bytes become data: one loader per type, any source
assets.setBudget(512L << 20);                    // bytes of unused assets kept for later
assets.addListener(gpuResources);                // the engine uploads on loaded, frees on evicted

AssetRef<MeshData> barrier = AssetRef.of(MESH, AssetId.parse("4f1c09d2a7e35b6c81f0e2d4c6a8b9e1"));
AssetRef<MeshData> farLod = barrier.part(2);     // assets that stream in parts: LOD levels, mip ranges
AssetRef<MeshData> rock = AssetRef.generated(MESH, ROCKS, new RockParams(2.5, 3), 42);

assets.acquire(barrier);                         // e.g. in CellLoader.attach
MeshData mesh = assets.get(barrier);             // null until loaded
assets.release(barrier);                         // in CellLoader.detach
```

- **Sources and loaders apart:** a source only opens byte streams by asset id and resource name (the main resource,
  and side resources a format names, like glTF buffers and images); transforms decompress or decrypt on the way. A
  loader reads its format from an `AssetInput`, so the same glTF loader reads from files, archives or the network.
  Sources are set once per cache, loaders registered once per asset type; a type may have a source of its own.
- **Loaded once:** a second acquire of an asset that is loading joins the load in flight; one that is loaded gets the
  same data. Equal references are the same asset: the same id and part, or the same generator, parameters and seed.
- **Kept for later:** at no holders an asset stays cached until the budget needs the room, least recently released
  first, so streaming back and forth does not reload. Assets in use are never evicted.
- **Uploaded once:** the engine's GPU resource cache listens to the asset cache and follows its residency (`loaded`,
  `evicted`), not node changes, so all instances share one upload.
- **At the frame boundary:** loads finish only there, those done by the same boundary in the order they started.
  Failed assets stay failed while held and load again when acquired after their last release.
- Identical content under two ids is caught at import time, by content hashes, not at runtime.

### Meshes: meshlets for a GPU-driven renderer

`MeshData` is a mesh as a GPU-driven renderer draws it, uploadable as it is: little-endian buffers with scalar layout,
one big GPU buffer per kind for all meshes, addressed by buffer device address. Geometry is in meshlets (up to 255
vertices and triangles; 64 / 124 by default when built), culled and drawn on the GPU, over quantized vertex streams:

| Buffer | Per | Bytes | Contents |
|---|---|---|---|
| positions | vertex | 8 | 3 × unorm16 in the mesh's quantization box (position-only passes: depth, shadows) |
| attributes | vertex | 4 each | interleaved, as the format says: normal and tangent octahedral, UVs float16, color, joints, weights |
| meshlet vertices | meshlet vertex | 4 | uint32 vertex index |
| meshlet triangles | triangle | 3 | 3 × uint8 index into the meshlet's vertices |
| meshlets | meshlet | 32 | offsets, counts, submesh, bounding sphere, normal cone (snorm8, rounded so it never culls wrongly) |
| cluster LOD | meshlet | 32 | own group's center and error, parent group's center and error |

- **Two GPU paths, one format:** task and mesh shaders (`VK_EXT_mesh_shader`) read meshlets directly; without them,
  compute culling writes multi-draw-indirect draws over `expandIndices()`, where a meshlet's first index is its
  triangle offset.
- **Detail, one rule:** draw a meshlet when its own group's projected error is at most the allowed error and its parent
  group's is more. Cluster hierarchies (Nanite-style, from simplifying groups of meshlets) give each group its own
  sphere and error, so detail varies across a large mesh without popping; `discreteLods()` makes authored LOD chains
  switch whole levels with the same rule.
- **Streaming:** levels are stored coarsest first, so a mesh can stream in from its coarsest level.
- **Memory:** a typical lit vertex takes 8 + 12 bytes instead of 48 as floats. The cache counts `MeshData.bytes()`:
  `AssetType.of("mesh", MeshData.class, MeshData::bytes)`.
- **Building:** meshes come from the mesh builder (#48, on meshoptimizer: vertex cache and fetch order, meshlets,
  simplification into a cluster hierarchy) or an importer, through `MeshData.builder(...)`.

### Multiplayer

- A server adds one interest source per player, with collision layers only; separate places (instances, other
  worlds) are separate worlds.
- Node ids are assigned by the authoritative side, separate from client-local ids.
- Replication sends each client the changes of the cells it is interested in, straight from the change logs.

## Usage

```kotlin
dependencies {
    implementation("com.truesim.scene:scene-core:0.16.0")
    implementation("com.truesim.scene:scene-world:0.16.0")     // streaming
    implementation("com.truesim.scene:scene-collision:0.16.0") // colliders and terrain heights per cell
    implementation("com.truesim.scene:scene-assets:0.16.0")    // asset references and the cache
}
```

## Building

```bash
./gradlew build
```

The libraries come from GitHub Packages, which needs a token with `read:packages` (`gpr.user` and `gpr.key` in
`~/.gradle/gradle.properties`, or `GITHUB_ACTOR` and `GITHUB_TOKEN`). To build against local checkouts instead, add
`--include-build ../common-math --include-build ../common-state`, and `-Ptruesim.build-logic=../build-logic` for a
local build-logic.
