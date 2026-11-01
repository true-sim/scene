package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * An {@link AnimationClip} bound to a rig ({@link Rig.Builder#clip}): its tracks to the rig's joints, once, and what the
 * rig adds to it while it is set up: events at times ({@link #event}) and curves ({@link #curve}). An additive clip
 * ({@link Rig.Builder#additive}) moves joints by its difference from a reference pose, on additive layers.
 *
 * <pre>{@code
 * Clip run = r.clip(RUN).event(0.0, FOOTSTEP).event(0.5, FOOTSTEP);
 * Clip breathe = r.additive(BREATHE, Clip.Reference.FIRST_FRAME);
 * }</pre>
 */
public final class Clip implements Motion {

    /** What an additive clip's moves are measured from. */
    public enum Reference {
        /** Its own first frame: a clip of a pose that changes. */
        FIRST_FRAME,
        /** The skeleton's rest pose. */
        REST_POSE
    }

    private final Rig rig;
    private final AnimationClip clip;
    private final Reference reference; // null: not additive
    final int[] trackJoint;            // by track: its joint, or -1 to skip it
    int cursorBase;                    // its tracks' first cursor in an animator's
    private double[][] referenceValues; // an additive clip's, by track
    private float[] eventTimes = new float[0];
    private AnimationEvent[] events = new AnimationEvent[0];
    private AnimationCurve[] curves = new AnimationCurve[0];
    private float[][] curveTimes = new float[0][];
    private float[][] curveValues = new float[0][];
    private double duration;
    private float[] markerTimes = new float[0];
    private SyncMarker[] markers = new SyncMarker[0];
    private SyncGroup group;
    private int rootTranslation = -1; // the tracks of the rig's root motion joint, or -1
    private int rootRotation = -1;

    @AllowAllocation("set up with its rig")
    Clip(Rig rig, AnimationClip clip, Reference reference, int[] trackJoint) {
        this.rig = rig;
        this.clip = Objects.requireNonNull(clip, "clip");
        this.reference = reference;
        this.trackJoint = trackJoint;
        this.duration = clip.duration();
        if (reference != null) {
            referenceValues = new double[trackJoint.length][];
            int[] cursor = new int[1];
            for (int t = 0; t < trackJoint.length; t++) {
                Track track = clip.tracks().get(t);
                double[] values = new double[Math.max(4, track.components())];
                if (reference == Reference.FIRST_FRAME || trackJoint[t] < 0
                    || track.property() == Track.Property.WEIGHTS) {
                    track.sample(0, cursor, 0, values); // weights rest at 0: their first frame then
                } else {
                    int offset = switch (track.property()) {
                        case TRANSLATION -> 0;
                        case ROTATION -> 3;
                        default -> 7;
                    };
                    for (int c = 0; c < track.components(); c++) {
                        values[c] = rig.skeleton().rest(trackJoint[t], offset + c);
                    }
                }
                referenceValues[t] = values;
            }
        }
    }

    @Override
    public Rig rig() {
        return rig;
    }

    /** The clip it plays. */
    public AnimationClip clip() {
        return clip;
    }

    /** Whether it is additive. */
    public boolean additive() {
        return reference != null;
    }

    /** Its additive reference, or null. */
    public Reference reference() {
        return reference;
    }

    /** Seconds it lasts: its clip's, or to its last curve key if later. */
    public double duration() {
        return duration;
    }

    /** Fires {@code event} as playback passes {@code time} (seconds into it); while the rig is set up. */
    @AllowAllocation("setting up a rig")
    public Clip event(double time, AnimationEvent event) {
        rig.checkSettingUp();
        Objects.requireNonNull(event, "event");
        if (!(time >= 0) || time == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time: " + time);
        }
        int at = 0;
        while (at < eventTimes.length && eventTimes[at] <= time) {
            at++;
        }
        float[] times = new float[eventTimes.length + 1];
        AnimationEvent[] named = new AnimationEvent[events.length + 1];
        System.arraycopy(eventTimes, 0, times, 0, at);
        System.arraycopy(events, 0, named, 0, at);
        times[at] = (float) time;
        named[at] = event;
        System.arraycopy(eventTimes, at, times, at + 1, eventTimes.length - at);
        System.arraycopy(events, at, named, at + 1, events.length - at);
        eventTimes = times;
        events = named;
        return this;
    }

    /**
     * Keys {@code curve} at {@code times} (seconds, ascending) to {@code values}, linear between them, held past the
     * ends; while the rig is set up.
     */
    @AllowAllocation("setting up a rig")
    public Clip curve(AnimationCurve curve, float[] times, float[] values) {
        rig.checkSettingUp();
        Objects.requireNonNull(curve, "curve");
        // A track checks the keys the same way
        new Track("", -1, Track.Property.WEIGHTS, Track.Interpolation.LINEAR, times, values, 1);
        int n = curves.length;
        curves = Arrays.copyOf(curves, n + 1);
        curveTimes = Arrays.copyOf(curveTimes, n + 1);
        curveValues = Arrays.copyOf(curveValues, n + 1);
        curves[n] = curve;
        curveTimes[n] = times.clone();
        curveValues[n] = values.clone();
        duration = Math.max(duration, times[times.length - 1]);
        rig.addCurve(curve);
        return this;
    }

    /** Marks {@code time} (seconds into it) with {@code marker}, for its sync group to line up by; while setting up. */
    @AllowAllocation("setting up a rig")
    public Clip marker(double time, SyncMarker marker) {
        rig.checkSettingUp();
        Objects.requireNonNull(marker, "marker");
        if (!(time >= 0) || time == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time: " + time);
        }
        int at = 0;
        while (at < markerTimes.length && markerTimes[at] <= time) {
            at++;
        }
        float[] times = new float[markerTimes.length + 1];
        SyncMarker[] named = new SyncMarker[markers.length + 1];
        System.arraycopy(markerTimes, 0, times, 0, at);
        System.arraycopy(markers, 0, named, 0, at);
        times[at] = (float) time;
        named[at] = marker;
        System.arraycopy(markerTimes, at, times, at + 1, markerTimes.length - at);
        System.arraycopy(markers, at, named, at + 1, markers.length - at);
        markerTimes = times;
        markers = named;
        return this;
    }

    /** Has it join {@code group} whenever it plays (null: none); while the rig is set up. */
    public Clip sync(SyncGroup group) {
        rig.checkSettingUp();
        this.group = group;
        return this;
    }

    /** The sync group it joins by default, or null. */
    public SyncGroup group() {
        return group;
    }

    int markerCount() {
        return markerTimes.length;
    }

    double markerTime(int i) {
        return markerTimes[i];
    }

    SyncMarker marker(int i) {
        return markers[i];
    }

    /** Finds its tracks of {@code joint}'s translation and rotation, for root motion. */
    void bindRoot(int joint) {
        var tracks = clip.tracks();
        for (int t = 0; t < trackJoint.length; t++) {
            if (trackJoint[t] == joint) {
                switch (tracks.get(t).property()) {
                    case TRANSLATION -> rootTranslation = t;
                    case ROTATION -> rootRotation = t;
                    default -> { }
                }
            }
        }
    }

    /**
     * Joint {@code joint}'s translation and rotation at {@code time} into {@code out[0..7)}: its tracks', or its rest
     * transform's where it has none. {@code cursor[0]} serves both tracks.
     */
    void root(int joint, double time, int[] cursor, double[] out, double[] scratch) {
        if (rootTranslation >= 0) {
            clip.tracks().get(rootTranslation).sample(time, cursor, 0, scratch);
            System.arraycopy(scratch, 0, out, 0, 3);
        } else {
            for (int i = 0; i < 3; i++) {
                out[i] = rig.skeleton().rest(joint, i);
            }
        }
        if (rootRotation >= 0) {
            clip.tracks().get(rootRotation).sample(time, cursor, 0, scratch);
            System.arraycopy(scratch, 0, out, 3, 4);
        } else {
            for (int i = 0; i < 4; i++) {
                out[3 + i] = rig.skeleton().rest(joint, 3 + i);
            }
        }
    }

    int eventCount() {
        return eventTimes.length;
    }

    double eventTime(int i) {
        return eventTimes[i];
    }

    AnimationEvent event(int i) {
        return events[i];
    }

    /**
     * Its values at {@code time} into {@code pose} (10 per joint) and {@code scalars} (morph weights, then curves, by
     * the rig's layout): absolute, or for an additive clip its moves from the reference (translation and weight
     * differences, the rotation relative to the reference's, scale ratios). Only what its tracks move is written.
     * Allocates nothing.
     */
    void sample(double time, int[] cursors, double[] pose, double[] scalars, double[] scratch) {
        var tracks = clip.tracks();
        for (int t = 0; t < trackJoint.length; t++) {
            int joint = trackJoint[t];
            if (joint < 0) {
                continue;
            }
            Track track = tracks.get(t);
            track.sample(time, cursors, cursorBase + t, scratch);
            double[] ref = reference != null ? referenceValues[t] : null;
            int base = Skeleton.TRANSFORM * joint;
            switch (track.property()) {
                case TRANSLATION -> {
                    for (int c = 0; c < 3; c++) {
                        pose[base + c] = ref == null ? scratch[c] : scratch[c] - ref[c];
                    }
                }
                case ROTATION -> {
                    if (ref == null) {
                        System.arraycopy(scratch, 0, pose, base + 3, 4);
                    } else {
                        // scratch * conjugate(ref)
                        double ax = scratch[0];
                        double ay = scratch[1];
                        double az = scratch[2];
                        double aw = scratch[3];
                        double bx = -ref[0];
                        double by = -ref[1];
                        double bz = -ref[2];
                        double bw = ref[3];
                        pose[base + 3] = aw * bx + ax * bw + ay * bz - az * by;
                        pose[base + 4] = aw * by - ax * bz + ay * bw + az * bx;
                        pose[base + 5] = aw * bz + ax * by - ay * bx + az * bw;
                        pose[base + 6] = aw * bw - ax * bx - ay * by - az * bz;
                    }
                }
                case SCALE -> {
                    for (int c = 0; c < 3; c++) {
                        pose[base + 7 + c] = ref == null ? scratch[c] : ref[c] == 0 ? 1 : scratch[c] / ref[c];
                    }
                }
                default -> {
                    int offset = rig.weightOffset(joint);
                    int count = Math.min(track.components(), rig.weightCount(joint));
                    for (int c = 0; c < count; c++) {
                        scalars[offset + c] = ref == null ? scratch[c] : scratch[c] - ref[c];
                    }
                }
            }
        }
        for (int c = 0; c < curves.length; c++) {
            double value = curveAt(c, time);
            scalars[rig.curveOffset() + rig.curveSlot(curves[c])] = reference == null ? value
                : value - (reference == Reference.FIRST_FRAME ? curveValues[c][0] : 0);
        }
    }

    /** Curve {@code c} at {@code time}: linear between keys, held past the ends. */
    private double curveAt(int c, double time) {
        float[] times = curveTimes[c];
        float[] values = curveValues[c];
        if (time <= times[0]) {
            return values[0];
        }
        int last = times.length - 1;
        if (time >= times[last]) {
            return values[last];
        }
        int k = 0;
        while (times[k + 1] <= time) {
            k++;
        }
        double u = (time - times[k]) / (times[k + 1] - times[k]);
        return values[k] + (values[k + 1] - values[k]) * u;
    }

    @Override
    public String toString() {
        return "Clip[" + clip.name() + (reference != null ? ", additive from " + reference : "") + "]";
    }
}
