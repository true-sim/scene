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
| `scene-assets`    | meshes with LOD chains and meshlets, materials, textures, animation, sprites, tilesets    | milestone 4 |
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
| request a new cell (starts its load)       | 3-26 µs (#26)               | 3-26 µs                            |
| attach a loaded cell (64 nodes)            | 18 µs                       | 12 µs                              |
| request and release an already loaded cell | 16 ns                       | 16 ns                              |
| 64 cells streaming into the loaded world   | 0.4 ms attach, 0.8 ms index | 0.3 ms attach, 0.1 ms index        |
| 64 cells leaving it                        | 0.1 ms                      | 0.1 ms                             |
| all 4,096 cells in one go: worst frame     | 191 ms                      | 25 ms                              |

Static nodes are indexed per cell: each streamed cell builds a small tree of its own, under a top-level tree of
cells, so a cell coming or going costs about its own size. Before that, any static change rebuilt one tree over
everything (154 ms at this size). A per-frame attach budget (#7) spreads bursts over frames.

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

### Multiplayer

- A server adds one interest source per player, with collision layers only; separate places (instances, other
  worlds) are separate worlds.
- Node ids are assigned by the authoritative side, separate from client-local ids.
- Replication sends each client the changes of the cells it is interested in, straight from the change logs.

## Usage

```kotlin
dependencies {
    implementation("com.truesim.scene:scene-core:0.2.0")
    implementation("com.truesim.scene:scene-world:0.2.0") // streaming
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
