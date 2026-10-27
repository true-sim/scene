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
    .layer(TERRAIN, Detail.screenError(2f), Range.horizon())
    .layer(PROPS, Detail.bands(150, 600, 2500))
    .layer(COLLISION, Detail.full(), Range.radius(200))
    .build();
static final InterestProfile SERVER_PLAYER = InterestProfile.builder()
    .layer(COLLISION, Detail.full(), Range.radius(300))     // physics only
    .build();

// Sources: one per camera, viewport or player; cheap and mutable.
InterestSource camera = world.addInterest(PLAYER_VIEW, Priority.HIGH);
camera.moveTo(position);            // every frame, allocation-free
world.update();                     // residency: starts and cancels loads within the budgets
```

- **Several kinds of detail:** LOD chains and mips inside assets, HLOD proxies for groups of cells (house → block →
  district → horizon), and per-layer resolutions such as a terrain pyramid out to the horizon.
- **Policies per layer:** always resident (full loading), distance bands, or screen-space error.
- **No holes:** a coarse level stays until its finer children are all loaded.
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
