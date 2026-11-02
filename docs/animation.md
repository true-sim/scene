# Animation: design

Skinned and morphing models, played, layered, blended and corrected by inverse kinematics, for games. Decided with the
user step by step, then shaped by a high-effort research review of Unreal, Unity, Godot 4, Bevy, ozz-animation and
ACL (its sources are listed at the end). Reviewed and decided with the user (A1 to A13).

## Decisions made

| | Decision | Why |
|---|---|---|
| A1 | **Deformation on the GPU, once a frame**: a compute pass skins and morphs each animated instance into its own vertex copy; every later pass (culling, visibility buffer, forward, shadows, ray tracing) reads it as static geometry. | One place; every path and shadow view gets it for free; ray tracing can refit. |
| A2 | **Animation evaluated on the CPU, in the scene**: sampling writes node transforms; joints are ordinary scene nodes, which gameplay reads and overrides (a sword in a hand, ragdolls, IK). | Deterministic, testable without a GPU, fits the scene graph and networking. |
| A3 | **Culling and LOD of deforming meshes**: the deformation pass recomputes each meshlet's sphere from the deformed vertices; cone culling off for them; LOD selection keeps the rest-pose errors, its spheres grown by how far the instance's joints move. | Correct culling; crowds keep their level of detail. |
| A4 | **Playback**: clips, layers and cross-fades; then (user) **everything a modern game needs**: per-joint masks, additive layers, synchronized blending, events, curves, root motion, two-bone IK and more. | A nice, complete API from the start. |

## Where things live

**scene, new module `scene-animation`** (core, assets, common-math, common-jobs; nothing of rendering, so servers use it
for root motion, events and hit tests):
- `AnimationClip`, an asset (`AnimationAssets.CLIP`): tracks (a joint by name; translation, rotation, scale, morph
  weights or a float curve; step, linear or cubic spline; key times and values as floats), events, sync markers.
- `Skeleton` (parents, names, rest pose; from a model's nodes); `Rig` (built once per model, shared by its instances:
  clips bound to joints, masks, layers, IK); `Animator` (one per spawned instance); `Animators` (all of a scene's, updated
  together, like `Renderables`).
- `Pose`: local transforms, in doubles as the scene graph keeps them; sampler, blending, IK solvers, `PoseModifier`.

**scene-assets** `MeshData` gains optional sections: a joint palette (mesh joint to skin joint: 8-bit joints still work for
skins of more than 256 joints), each joint's rest-pose box of the vertices it moves (for bounds), and morph targets
(sparse per-vertex deltas of position, normal, tangent, and each target's largest move).

**scene-render**: `ModelData` gains skins (joints, inverse bind matrices), each node's skin, default morph weights and
camera, cameras, and the model's animations (`model.animation("Walk")`). `Renderables` binds a skin and morph weights to a
node; `spawn()` sets them.

**scene-gltf** reads skins, `JOINTS_n`/`WEIGHTS_n` (the four largest, renormalized), morph targets, animations
(`file.gltf#animation/N`) and cameras, and keeps the map from glTF node indices to the model's.

**engine-render**: `GeometryPool.addDeformed(mesh)` gives an instance its own vertices and meshlet records, so its
deformed copy draws as any static mesh on every path, with no change to the draw shaders. A `Deformation` frame graph
pass after the uploads: morph and skin (a thread per vertex), meshlet spheres and grown LOD records, later BLAS refits.

**engine-scene**: `SceneSync` takes a deformed entry for a skinned renderable and each frame uploads its joint matrices
(relative to the mesh's node, computed in doubles) and its bounds. **engine-app**: an `Animation` plugin runs a world's
animators.

## A frame

`UPDATE` (the game sets inputs: speed, targets, what plays) → **`ANIMATE`** → `LATE` (the game reads and overrides joints)
→ scene sync → render. `ANIMATE`, new between `UPDATE` and `LATE`:
1. In parallel, each animator alone: advance times (sync leaders first), collect events and root motion, sample, blend
   layers, apply overrides, IK and modifiers. Results do not depend on the thread count.
2. In order: write joints into the scene graph (a new one-call `setLocal`), morph weights, dispatch events, apply root
   motion.
3. World transforms brought up to date, so `LATE` reads this frame's joints.

On the GPU: uploads → deformation (skipping instances that did not change) → ray tracing refits → culling and drawing.

## The API

```java
// Names as typed constants, declared once.
static final AnimationEvent FOOTSTEP = AnimationEvent.of("footstep");
static final AnimationCurve BLINK = AnimationCurve.of("blink");
static final SyncGroup LOCOMOTION = SyncGroup.of("locomotion");
static final SyncMarker LEFT_DOWN = SyncMarker.of("leftFootDown"), RIGHT_DOWN = SyncMarker.of("rightFootDown");

// Quick: play a model's animation.
long[] nodes = knight.spawn(scene, renderables, root);
Animator a = animators.add(root, knight, nodes);
a.play(knight.animation("Walk")).speed(1.2f).time(0.5);

// Complete: a rig built once per model, shared by all of its instances.
Rig.Builder r = Rig.builder(knight);
Joint spine = r.joint("spine_01"), head = r.joint("head"), neck = r.joint("neck_01");
Joint thighL = r.joint("thigh_l"), calfL = r.joint("calf_l"), footL = r.joint("foot_l"), handR = r.joint("hand_r");
Clip idle = r.clip(knight.animation("Idle"));
Clip walk = r.clip(knight.animation("Walk")).marker(0.0, LEFT_DOWN).marker(0.5, RIGHT_DOWN);
Clip run = r.clip(RUN).marker(0.0, LEFT_DOWN).marker(0.5, RIGHT_DOWN).event(0.0, FOOTSTEP).event(0.5, FOOTSTEP);
Clip slash = r.clip(SLASH);
Clip breathe = r.additive(BREATHE, AdditiveReference.FIRST_FRAME);
BlendSpace1 move = r.blendSpace1().at(0, idle).at(1.6, walk).at(5.0, run).sync(LOCOMOTION);
Mask upper = r.mask().include(spine).build();            // spine and below; weights per joint, exclusions
Rig.Layer base = r.layer();                               // overrides, full body
Rig.Layer upperBody = r.layer().mask(upper);
Rig.Layer breath = r.layer().additive();
TwoBoneIk leftLeg = r.twoBoneIk(thighL, calfL, footL).stretch(1.0, 1.1);
LookAt look = r.lookAt(head, Axis.Z).chain(neck, 0.4).chain(spine, 0.2).limit(Maths.toRadians(70));
r.rootMotion(r.joint("root"), RootMotion.XZ_YAW);
r.bind(BLINK, CurveSink.morph(face, "blink"));             // a curve drives a morph target, or code reads it
r.modifier(new FootPlacement(leftLeg, rightLeg, pelvis, ground));
Rig rig = r.build();

Animator a = animators.add(root, rig, nodes);
a.layer(base).play(move);
a.layer(breath).play(breathe).weight(0.5f);

// Each frame, in UPDATE: primitives only, nothing allocated.
a.input(move, speed);                                     // the blend space's parameter
if (attack) a.layer(upperBody).playOnce(slash, 0.10f, 0.20f).speed(1.25f); // fades in, and out at its end
a.layer(base).play(jump, 0.2f);                           // cross-fades over 0.2 s
a.ik(leftLeg).target(x, y, z).pole(px, py, pz).weight(1);
a.lookAt(look).target(tx, ty, tz).weight(0.8f);
a.override(head, Space.MODEL).rotation(qx, qy, qz, qw).weight(1);
a.release(handR);                                         // the game's from now on (physics, a held prop)
double blink = a.curve(BLINK);
RootMotion m = a.rootMotion();                            // this frame's: dx(), dz(), yaw()
long sword = scene.create(a.node(handR));                 // joints are scene nodes: attach to them
animators.listen(FOOTSTEP, (node, event, weight) -> audio.footstep(node));
a.layer(upperBody).state(slash).onEnd(...);               // a state's begin, blend-out, end
```

Setup objects (`Joint`, `Clip`, `Mask`, layers, IK) are immutable handles; what an instance changes each frame is made
once and reused: calls take primitives, look nothing up by name and allocate nothing. No state machine in the first
version: games drive layers and fades in code; a state machine helper comes later, on top.

## How it works

- **Sampling**, as glTF says: clamped to the first and last keys; step, linear (rotations by slerp, the short way) and
  cubic spline (rotations normalized after); a cursor per track, so playing forward never searches.
- **Blending**: weighted sums (rotations aligned to the first, then normalized); a layer overrides what is below by its
  weight times its mask's weight for each joint; an **additive** layer adds its clip's difference from a reference (its
  first frame, or the rest pose), scaled by its weight; a **cross-fade** ramps the new state up and the others down.
- **Sync groups**: the heaviest state leads (or a pinned one); every member keeps the leader's phase, by markers where
  they share them (left foot down, right foot down), else by normalized time. Followers fire no events.
- **Events** fire as playback passes them, across a loop's end and backwards too, only for states weighing more than a
  threshold; they are queued per animator and dispatched in a fixed order (deterministic).
- **Root motion**: the root joint's motion between frames, blended by the states' weights, taken out of the pose on the
  chosen axes; the game applies it, or the animator moves its node.
- **Two-bone IK** (Holden's law-of-cosines solution): reach clamped, bend towards the pole, a twist putting the middle
  joint in the pole's plane; softening, stretching, the end joint's rotation from the target, a weight. **Look-at/aim**
  as ozz's aim job along a chain with weights, within an angle limit. **Foot placement** is a `PoseModifier` (the game
  gives the ground: terrain heights or collision): the pelvis lowered, two-bone IK per leg, feet along the ground.
- **On the GPU**: linear blend skinning (dual quaternions later, per skin); morph deltas gathered per vertex (no atomics:
  deterministic); normals by the joints' cofactor matrices, tangents re-orthogonalized.
- **Bounds**: the instance's box is the union of each joint's rest box moved by its matrix (conservative: a skinned vertex
  is a mix of its joints' moves), plus the morphs' largest moves; each meshlet's sphere is recomputed from its deformed
  vertices; LOD spheres grow by the largest distance any joint moves its box, which keeps the hierarchy's order.

## Steps (a PR each, tested, with the Khronos models that check it)

| # | Repo | What | Checked with |
|---|---|---|---|
| 1 | scene | Node index map, skins, joints and weights, joint palette and boxes | SimpleSkin, RiggedSimple, RiggedFigure, CesiumMan, BrainStem, RecursiveSkeletons |
| 2 | scene | Morph targets | SimpleMorph, MorphPrimitivesTest, MorphStressTest, AnimatedMorphCube |
| 3 | scene | Cameras | Cameras |
| 4 | scene | Clips, sampling, skeleton, quick animator, `Animators`, `setLocal` | InterpolationTest, AnimatedCube, BoxAnimated, AnimatedTriangle, CesiumMilkTruck |
| 5 | engine | Deformed instances, joint matrices, skinning, bounds, LOD; the demo | SimpleSkin, RiggedFigure, CesiumMan, Fox, BrainStem |
| 6 | engine | Morph targets on the GPU | AnimatedMorphCube, MorphStressTest |
| 7 | scene | Layers, masks, additive, cross-fades, blend spaces, events, curves | Fox (survey, walk, run) |
| 8 | scene | Sync groups and markers, root motion | Fox walk and run |
| 9 | scene | Two-bone IK, look-at, modifiers, foot placement | analytic chains, RiggedFigure, Fox |
| 9b | scene | FABRIK chains with joint limits | analytic chains, a tail or tentacle rig |
| 10 | engine | Ray tracing refits, skipping unchanged instances, animation LOD (rate, offscreen) | BrainStem, Fox |
| 11 | both | `KHR_animation_pointer` (materials, lights, cameras, visibility) | AnimatedColorsCube, PotOfCoalsAnimationPointer |

## Further decisions (with the user, one at a time)

| | Decision |
|---|---|
| A5 | **Update rate**: animators advance once per rendered frame on the world clock; an animator may opt into fixed steps (`FIXED`) instead, for characters moved by root motion and on servers, its joints then interpolated for rendering. |
| A6 | **Motion vectors** of deforming meshes: decided when temporal antialiasing comes; the deformed copy is laid out so last frame's positions can be added beside it. |
| A7 | **Sharing clips**: a clip binds to any rig whose joints have the same names; humanoid retargeting later, nothing in the data blocking it. |
| A8 | **Events and markers** come from code (`clip.event(0.5, FOOTSTEP)`); a sidecar file beside the model later, for tools. |
| A9 | **Curves** drive morph weights and are read by game code; material parameters when the engine has per-instance ones. |
| A10 | **Coarser levels of skinned meshes** are simplified with the joint weights as attributes, so collapses stay within a joint's influence. |
| A11 | **Influences**: the four largest per vertex, renormalized; an eight-influence attribute later, where a model needs it. |
| A12 | **IK**: two-bone, look-at/aim and foot placement first; a FABRIK chain solver with joint limits next, on the same `PoseModifier` hook. |
| A13 | **Ground probes** for foot placement run in the serial part of `ANIMATE` until collision queries are verified thread-safe, then in the parallel part. |
| A14 | **`KHR_animation_pointer`** (step 11): generic pointer tracks (`Track.Property.POINTER`, an `AnimationPointer` naming a material's data field, a light's or camera's value, or a node's visibility, with its rest value), blended as curves from their rests and handed to a `PointerSink` while the animator plays. Materials are animated as the asset (glTF's meaning: every instance of the material follows the last animator writing it), not per instance; lights and cameras are the game's (it reads the sink or `Animator.value`); visibility through layers (`NodeVisibility`). |

## What changes in existing code

- glTF importer: skins, animations, JOINTS/WEIGHTS are not read; new attributes would default to colors there, so they
  get explicit cases; the node index map is kept.
- `ModelData.Node`: skin, weights, camera; `spawn` binds skins.
- `MeshBuilder`: morph deltas and joint boxes follow its vertex remap.
- `SceneGraph`: a one-call `setLocal(node, ...)`, as the README promises primitive overloads on hot paths.
- `SceneSync`: its "more than half changed, rewrite all" rule counts only renderable nodes, not joints.
- `RenderableBounds`: animated nodes take the box above.
- engine: `GeometryProvider`'s planned separate skinned provider is dropped (A1); ray tracing gets refittable BLAS for
  deformed instances; the frame steps gain `ANIMATE` (application-api.md D90/D124).

## Sources

glTF 2.0 specification and KHR_animation_pointer (Khronos); Unreal Engine documentation on sync groups, layered
animation, root motion, notifies, two-bone IK, curves and inertialization; Unity's animation layers, Playables and
Animation Rigging two-bone IK; Godot's SkeletonModifier3D and "Inverse kinematics returns to Godot 4.6"; Bevy 0.15 and
its AnimationGraph; ozz-animation's runtime, blend, additive, two-bone IK and look-at samples; ACL (Frechette); Daniel
Holden, "Simple two joint IK" and "Dead blending"; Kavan et al., dual quaternion skinning.
