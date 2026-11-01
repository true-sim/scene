package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How a model animates, built once and shared by every instance's {@link Animator}: its skeleton, its clips bound to
 * the joints, masks, blend spaces and layers. Everything a frame changes is on the animator; the rig holds only setup,
 * so instances cost little. Layers apply in the order made: an override layer blends its pose over the ones below by
 * its weight and mask, an additive layer adds its clips' moves to them.
 *
 * <pre>{@code
 * Rig.Builder r = Rig.builder(model.skeleton());
 * Joint spine = r.joint("spine_01");
 * Clip idle = r.clip(idleClip), walk = r.clip(walkClip), run = r.clip(runClip).event(0.5, FOOTSTEP);
 * BlendSpace1 move = r.blendSpace1().at(0, idle).at(1.6, walk).at(5.0, run);
 * Rig.Layer base = r.layer();
 * Rig.Layer upper = r.layer().mask(r.mask().include(spine).build());
 * Rig.Layer breath = r.layer().additive();
 * Rig rig = r.build();
 * Animator a = animators.add(rig, nodes);
 * }</pre>
 *
 * <p>{@link #of(Skeleton)} is a rig of one layer that binds clips as they are first played ({@link Animator#play}).
 */
public final class Rig {

    private final Skeleton skeleton;
    private final boolean open; // binds clips when first played
    private boolean settingUp;
    private final List<Clip> clips = new ArrayList<>();
    private final List<AnimationClip> sources = new ArrayList<>(); // an open rig's clips, by what they bind
    private final List<Layer> layers = new ArrayList<>();
    private final List<AnimationCurve> curves = new ArrayList<>();
    private int blendSpaces;
    // The layout of an animator's values: per joint whether a clip moves it, its morph weights' offset and count;
    // then the curves.
    private boolean[] animated;
    private int[] weightOffset;
    private int[] weightCount;
    private int curveOffset;
    private int scalarCount;
    private int cursorCount;
    private int version; // changes when an open rig binds a clip

    @AllowAllocation("setting up a rig")
    private Rig(Skeleton skeleton, boolean open) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        this.open = open;
        this.settingUp = !open;
        layout();
    }

    /** Starts a rig of {@code skeleton}'s nodes. */
    @AllowAllocation("setting up a rig")
    public static Builder builder(Skeleton skeleton) {
        return new Builder(new Rig(skeleton, false));
    }

    /** A rig of one override layer that binds clips as they are first played: the quick way. */
    @AllowAllocation("setting up a rig")
    public static Rig of(Skeleton skeleton) {
        Rig rig = new Rig(skeleton, true);
        rig.layers.add(new Layer(rig, 0));
        return rig;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    public List<Layer> layers() {
        return List.copyOf(layers);
    }

    int layerCount() {
        return layers.size();
    }

    Layer layer(int i) {
        return layers.get(i);
    }

    int blendSpaceCount() {
        return blendSpaces;
    }

    int clipCount() {
        return clips.size();
    }

    Clip clip(int i) {
        return clips.get(i);
    }

    int version() {
        return version;
    }

    boolean animated(int joint) {
        return animated[joint];
    }

    int weightOffset(int joint) {
        return weightOffset[joint];
    }

    int weightCount(int joint) {
        return weightCount[joint];
    }

    int curveOffset() {
        return curveOffset;
    }

    int scalarCount() {
        return scalarCount;
    }

    int cursorCount() {
        return cursorCount;
    }

    /** The slot of {@code curve} among the curves, or -1 if no clip keys it. */
    int curveSlot(AnimationCurve curve) {
        for (int i = 0; i < curves.size(); i++) {
            if (curves.get(i) == curve) {
                return i;
            }
        }
        return -1;
    }

    void checkSettingUp() {
        if (!settingUp) {
            throw new IllegalStateException("The rig is built: its setup does not change");
        }
    }

    void checkOwn(Object part, Rig of) {
        if (of != this) {
            throw new IllegalArgumentException("Not of this rig: " + part);
        }
    }

    @AllowAllocation("setting up a rig")
    void addCurve(AnimationCurve curve) {
        if (curveSlot(curve) < 0) {
            curves.add(curve);
        }
    }

    /**
     * The clip of an open rig that plays {@code source}: bound when first asked for, then kept. Animators of the rig
     * make room for it as they next play.
     */
    @AllowAllocation("binding a clip the first time it plays")
    Clip clipOf(AnimationClip source) {
        if (!open) {
            throw new IllegalStateException("A built rig plays its own clips: set them up with Rig.builder");
        }
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i) == source) {
                return clips.get(i);
            }
        }
        Clip clip = bind(source, null);
        sources.add(source);
        layout();
        version++;
        return clip;
    }

    @AllowAllocation("setting up a rig")
    private Clip bind(AnimationClip source, Clip.Reference reference) {
        List<Track> tracks = source.tracks();
        int[] joints = new int[tracks.size()];
        for (int t = 0; t < joints.length; t++) {
            Track track = tracks.get(t);
            int index = track.node();
            if (index >= 0 && index < skeleton.size() && skeleton.name(index).equals(track.nodeName())) {
                joints[t] = index;
            } else {
                joints[t] = track.nodeName().isEmpty() ? -1 : skeleton.find(track.nodeName());
            }
        }
        Clip clip = new Clip(this, source, reference, joints);
        clips.add(clip);
        return clip;
    }

    /** Works out the animators' layout from the clips: which joints move, the morph weights, the curves. */
    @AllowAllocation("setting up a rig")
    private void layout() {
        int n = skeleton.size();
        animated = new boolean[n];
        weightCount = new int[n];
        weightOffset = new int[n];
        int cursors = 0;
        for (Clip clip : clips) {
            clip.cursorBase = cursors;
            cursors += clip.trackJoint.length;
            List<Track> tracks = clip.clip().tracks();
            for (int t = 0; t < clip.trackJoint.length; t++) {
                int joint = clip.trackJoint[t];
                if (joint < 0) {
                    continue;
                }
                Track track = tracks.get(t);
                if (track.property() == Track.Property.WEIGHTS) {
                    weightCount[joint] = Math.max(weightCount[joint], track.components());
                } else {
                    animated[joint] = true;
                }
            }
        }
        int total = 0;
        for (int j = 0; j < n; j++) {
            weightOffset[j] = total;
            total += weightCount[j];
        }
        curveOffset = total;
        scalarCount = total + curves.size();
        cursorCount = cursors;
    }

    /**
     * A layer: override (blending its pose over those below by its weight, times its mask's per joint) or additive
     * (adding its clips' moves). Set up while the rig is; animators play on it ({@link Animator#layer}).
     */
    public static final class Layer {

        private final Rig rig;
        private final int index;
        private Mask mask;
        private boolean additive;

        Layer(Rig rig, int index) {
            this.rig = rig;
            this.index = index;
        }

        public Rig rig() {
            return rig;
        }

        /** Its place among the rig's layers, applied in order. */
        public int index() {
            return index;
        }

        /** Moves the joints by {@code mask}'s weights only. */
        public Layer mask(Mask mask) {
            rig.checkSettingUp();
            rig.checkOwn(mask, mask.rig());
            this.mask = mask;
            return this;
        }

        /** Adds its clips' moves (additive clips) to the layers below instead of blending over them. */
        public Layer additive() {
            rig.checkSettingUp();
            this.additive = true;
            return this;
        }

        /** Its mask, or null: every joint. */
        public Mask mask() {
            return mask;
        }

        public boolean isAdditive() {
            return additive;
        }

        float weight(int joint) {
            return mask == null ? 1 : mask.weight(joint);
        }

        @Override
        public String toString() {
            return "Layer[" + index + (additive ? ", additive" : "") + (mask != null ? ", masked" : "") + "]";
        }
    }

    /** Sets up a rig: joints, clips, blend spaces, masks and layers, then {@link #build()}. */
    public static final class Builder {

        private final Rig rig;

        private Builder(Rig rig) {
            this.rig = rig;
        }

        /** The joint called {@code name}. */
        @AllowAllocation("setting up a rig")
        public Joint joint(String name) {
            rig.checkSettingUp();
            int index = rig.skeleton.find(name);
            if (index < 0) {
                throw new IllegalArgumentException("No joint " + name);
            }
            return new Joint(index, name);
        }

        /** {@code clip} bound to the rig's joints: by index where it was made for this skeleton's model, else by name. */
        @AllowAllocation("setting up a rig")
        public Clip clip(AnimationClip clip) {
            rig.checkSettingUp();
            return rig.bind(clip, null);
        }

        /** {@code clip} as an additive clip: its moves from {@code reference}, for additive layers. */
        @AllowAllocation("setting up a rig")
        public Clip additive(AnimationClip clip, Clip.Reference reference) {
            rig.checkSettingUp();
            return rig.bind(clip, Objects.requireNonNull(reference, "reference"));
        }

        /** A blend space of clips along one parameter; place them with {@link BlendSpace1#at}. */
        @AllowAllocation("setting up a rig")
        public BlendSpace1 blendSpace1() {
            rig.checkSettingUp();
            return new BlendSpace1(rig, rig.blendSpaces++);
        }

        /** A mask's builder. */
        @AllowAllocation("setting up a rig")
        public Mask.Builder mask() {
            rig.checkSettingUp();
            return new Mask.Builder(rig);
        }

        /** A layer, over those made before. */
        @AllowAllocation("setting up a rig")
        public Layer layer() {
            rig.checkSettingUp();
            Layer layer = new Layer(rig, rig.layers.size());
            rig.layers.add(layer);
            return layer;
        }

        /** The rig, its setup final: a base layer of its own if none was made. */
        @AllowAllocation("setting up a rig")
        public Rig build() {
            rig.checkSettingUp();
            if (rig.layers.isEmpty()) {
                layer();
            }
            rig.layout();
            rig.settingUp = false;
            return rig;
        }
    }
}
