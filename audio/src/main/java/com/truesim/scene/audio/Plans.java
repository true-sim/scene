package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.random.Hash;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an instance's sound into its voices, from the instance's seed, switch values and this store's history of
 * random picks: which clips play, after which delays and voices, with what gain and rate drawn, and which curves and
 * blends change them as parameters move. Each sound is flattened once per store (its nodes numbered, so every draw has
 * its own salt: {@code Hash.combine(seed, node * 4 + purpose)}); after that, planning allocates nothing.
 *
 * <p>Nothing here depends on slots, threads or timing: the same seed, switches and history give the same voices on
 * any machine (AU10).
 */
final class Plans {

    /** What a voice plays. */
    static final byte CLIP = 0;
    static final byte INPUT = 1;
    static final byte PATCH = 2;
    static final byte SCATTER = 3;

    /** When a voice plays. */
    static final byte NORMAL = 0;
    /** The loop of a {@link Segments}: repeats until the instance stops. */
    static final byte LOOP = 1;
    /** The outro of a {@link Segments}: plays when the instance stops. */
    static final byte OUTRO = 2;

    /** What a factor of a voice's gain or rate reads. */
    static final byte VOLUME_CURVE = 0;
    static final byte PITCH_CURVE = 1;
    static final byte BLEND = 2;

    /** A sound flattened: its nodes in walk order, with where each Random node keeps its history in the store. */
    static final class Flat {
        final Sound sound;
        final SoundNode[] nodes;
        final int[] history;
        final int[] clipIndex;

        @AllowAllocation("once per sound")
        Flat(Sound sound, Plans plans) {
            this.sound = sound;
            List<SoundNode> list = new java.util.ArrayList<>();
            walk(sound.root(), list);
            nodes = list.toArray(new SoundNode[0]);
            history = new int[nodes.length];
            clipIndex = new int[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                if (nodes[i] instanceof Random r) {
                    history[i] = plans.reserve(r.children().size() + 2);
                }
                clipIndex[i] = nodes[i] instanceof Play p && !p.isInput() ? sound.clips().indexOf(p.clip()) : -1;
            }
        }

        @AllowAllocation("once per sound")
        private static void walk(SoundNode node, List<SoundNode> out) {
            out.add(node);
            switch (node) {
                case Random r -> r.children().forEach(c -> walk(c, out));
                case Sequence s -> s.children().forEach(c -> walk(c, out));
                case Layer l -> l.children().forEach(c -> walk(c, out));
                case Blend b -> b.children().forEach(c -> walk(c, out));
                case Switch<?> s -> {
                    s.cases().forEach(c -> walk(c.node(), out));
                    if (s.otherwise() != null) {
                        walk(s.otherwise(), out);
                    }
                }
                case Segments s -> {
                    if (s.intro() != null) {
                        walk(s.intro(), out);
                    }
                    walk(s.loop(), out);
                    if (s.outro() != null) {
                        walk(s.outro(), out);
                    }
                }
                case Scatter s -> walk(s.sound(), out);
                case Play p -> { }
                case Patch p -> { }
            }
        }

        /**
         * The number of {@code node} in walk order, searched from {@code from}: a child comes after its parent, and
         * the first of a parent's subtree to match is the child (by identity; an equal node elsewhere plays the same).
         */
        int index(SoundNode node, int from) {
            int i = from;
            while (nodes[i] != node) {
                i++;
            }
            return i;
        }
    }

    /** One instance's voices. Reused when its slot is. */
    static final class Plan {
        int count;
        int[] clip = new int[4];
        byte[] kind = new byte[4];
        byte[] role = new byte[4];
        float[] delay = new float[4];
        int[] follows = new int[4];
        float[] gain = new float[4];
        float[] rate = new float[4];
        boolean[] looped = new boolean[4];
        SoundNode[] node = new SoundNode[4];
        Release[] release = new Release[4];
        int[] factorStart = new int[4];
        int[] factorCount = new int[4];
        int factors;
        Object[] factorRef = new Object[8];
        int[] factorArg = new int[8];
        byte[] factorKind = new byte[8];

        @AllowAllocation("growing with the voices of a sound")
        void growVoices() {
            int n = 2 * clip.length;
            clip = Arrays.copyOf(clip, n);
            kind = Arrays.copyOf(kind, n);
            role = Arrays.copyOf(role, n);
            delay = Arrays.copyOf(delay, n);
            follows = Arrays.copyOf(follows, n);
            gain = Arrays.copyOf(gain, n);
            rate = Arrays.copyOf(rate, n);
            looped = Arrays.copyOf(looped, n);
            node = Arrays.copyOf(node, n);
            release = Arrays.copyOf(release, n);
            factorStart = Arrays.copyOf(factorStart, n);
            factorCount = Arrays.copyOf(factorCount, n);
        }

        @AllowAllocation("growing with the curves of a sound")
        void growFactors() {
            int n = 2 * factorRef.length;
            factorRef = Arrays.copyOf(factorRef, n);
            factorArg = Arrays.copyOf(factorArg, n);
            factorKind = Arrays.copyOf(factorKind, n);
        }
    }

    private final Map<Sound, Flat> flats = new IdentityHashMap<>();
    private int[] history = new int[64];
    private int historyUsed;

    // The walk's state: the factors on the path from the root, as a stack.
    private Object[] stackRef = new Object[16];
    private int[] stackArg = new int[16];
    private byte[] stackKind = new byte[16];
    private int depth;

    /** {@code sound} flattened for this store; made on its first play. */
    Flat flat(Sound sound) {
        Flat flat = flats.get(sound);
        return flat != null ? flat : make(sound);
    }

    @AllowAllocation("once per sound")
    private Flat make(Sound sound) {
        Flat flat = new Flat(sound, this);
        flats.put(sound, flat);
        return flat;
    }

    @AllowAllocation("once per sound")
    private int reserve(int words) {
        if (historyUsed + words > history.length) {
            history = Arrays.copyOf(history, Math.max(2 * history.length, historyUsed + words));
        }
        int at = historyUsed;
        historyUsed += words;
        return at;
    }

    /** Fills {@code plan} with the voices of {@code flat}'s sound for {@code seed} and {@code switches}. */
    void plan(Flat flat, int seed, int[] switches, Plan plan) {
        plan.count = 0;
        plan.factors = 0;
        depth = 0;
        Sound sound = flat.sound;
        float gain = sound.volume().factor(Hash.random(seed, -1));
        float rate = sound.pitch().factor(Hash.random(seed, -2));
        walk(flat, 0, seed, switches, plan, 0, gain, rate, -1, NORMAL, sound.loop());
    }

    /**
     * Plans node {@code n} and below, {@code delay} seconds in, after voice {@code follows}; returns the last voice it
     * planned, or {@code follows} when it planned none.
     */
    private int walk(Flat flat, int n, int seed, int[] switches, Plan plan, float delay, float gain, float rate,
            int follows, byte role, boolean loop) {
        SoundNode node = flat.nodes[n];
        Shaping s = node.shaping();
        gain *= s.volume().factor(Hash.random(seed, 4 * n));
        rate *= s.pitch().factor(Hash.random(seed, 4 * n + 1));
        delay += (float) s.delay();
        int mark = depth;
        for (int i = 0; i < s.volumeCurves().size(); i++) {
            push(s.volumeCurves().get(i), 0, VOLUME_CURVE);
        }
        for (int i = 0; i < s.pitchCurves().size(); i++) {
            push(s.pitchCurves().get(i), 0, PITCH_CURVE);
        }
        int last = follows;
        switch (node) {
            case Play p -> last = voice(plan, p.isInput() ? INPUT : CLIP, flat.clipIndex[n], node, delay, gain, rate,
                follows, role, loop);
            case Patch p -> last = voice(plan, PATCH, -1, node, delay, gain, rate, follows, role, loop);
            case Scatter sc -> last = voice(plan, SCATTER, -1, node, delay, gain, rate, follows, role, loop);
            case Random r -> {
                int pick = pick(flat, n, r, seed);
                last = walk(flat, child(flat, n, r.children(), pick), seed, switches, plan, delay, gain, rate, follows,
                    role, loop);
            }
            case Sequence sq -> {
                for (int i = 0; i < sq.children().size(); i++) {
                    last = walk(flat, child(flat, n, sq.children(), i), seed, switches, plan, i == 0 ? delay : 0,
                        gain, rate, last, role, loop);
                }
            }
            case Layer l -> {
                for (int i = 0; i < l.children().size(); i++) {
                    last = walk(flat, child(flat, n, l.children(), i), seed, switches, plan, delay, gain, rate,
                        follows, role, loop);
                }
            }
            case Blend b -> {
                for (int i = 0; i < b.children().size(); i++) {
                    push(b, i, BLEND);
                    last = walk(flat, child(flat, n, b.children(), i), seed, switches, plan, delay, gain, rate,
                        follows, role, loop);
                    depth--;
                }
            }
            case Switch<?> sw -> {
                // a value without a case has otherwise: the sound's check made sure
                SoundNode chosen = sw.child(switches[flat.sound.switchIndex(sw.group())]);
                last = walk(flat, flat.index(chosen, n + 1), seed, switches, plan, delay, gain, rate, follows, role,
                    loop);
            }
            case Segments sg -> {
                int intro = follows;
                int from = n + 1;
                if (sg.intro() != null) {
                    from = flat.index(sg.intro(), from);
                    intro = walk(flat, from, seed, switches, plan, delay, gain, rate, follows, role, false);
                }
                int loopNode = flat.index(sg.loop(), from);
                int first = plan.count;
                last = walk(flat, loopNode, seed, switches, plan, sg.intro() != null ? 0 : delay, gain, rate, intro,
                    LOOP, true);
                for (int v = first; v < plan.count; v++) {
                    plan.release[v] = sg.release(); // how the loop's voices end when the instance stops
                }
                if (sg.outro() != null) {
                    walk(flat, flat.index(sg.outro(), loopNode + 1), seed, switches, plan, 0, gain, rate, -1, OUTRO,
                        false);
                }
            }
        }
        depth = mark;
        return last;
    }

    /** The walk-order number of child {@code i} of node {@code n}. */
    private static int child(Flat flat, int n, List<SoundNode> children, int i) {
        return flat.index(children.get(i), n + 1);
    }

    private int voice(Plan plan, byte kind, int clip, SoundNode node, float delay, float gain, float rate, int follows,
            byte role, boolean loop) {
        if (plan.count == plan.clip.length) {
            plan.growVoices();
        }
        int v = plan.count++;
        plan.clip[v] = clip;
        plan.kind[v] = kind;
        plan.role[v] = role;
        plan.delay[v] = delay;
        plan.follows[v] = follows;
        plan.gain[v] = gain;
        plan.rate[v] = rate;
        plan.looped[v] = loop;
        plan.node[v] = node;
        plan.release[v] = null;
        plan.factorStart[v] = plan.factors;
        plan.factorCount[v] = depth;
        for (int i = 0; i < depth; i++) {
            if (plan.factors == plan.factorRef.length) {
                plan.growFactors();
            }
            plan.factorRef[plan.factors] = stackRef[i];
            plan.factorArg[plan.factors] = stackArg[i];
            plan.factorKind[plan.factors++] = stackKind[i];
        }
        return v;
    }

    private void push(Object ref, int arg, byte kind) {
        if (depth == stackRef.length) {
            growStack();
        }
        stackRef[depth] = ref;
        stackArg[depth] = arg;
        stackKind[depth++] = kind;
    }

    @AllowAllocation("a deeper tree than any before")
    private void growStack() {
        stackRef = Arrays.copyOf(stackRef, 2 * depth);
        stackArg = Arrays.copyOf(stackArg, 2 * depth);
        stackKind = Arrays.copyOf(stackKind, 2 * depth);
    }

    /**
     * Picks a child of Random node {@code n} by weight, skipping the store's last {@code noRepeat} picks of it, and
     * with shuffle the children already played this round. History: [count, head, last picks ring | played flags].
     */
    private int pick(Flat flat, int n, Random r, int seed) {
        int base = flat.history[n];
        int children = r.children().size();
        int[] h = history;
        if (r.shuffled() && h[base] >= children) { // a round is over
            Arrays.fill(h, base, base + 2 + children, 0);
        }
        float total = 0;
        int lastAllowed = 0;
        for (int i = 0; i < children; i++) {
            if (allowed(h, base, r, i, children)) {
                total += r.weights().get(i);
                lastAllowed = i;
            }
        }
        // the last allowed child takes what is left, so rounding in the sums can never pick none
        float u = Hash.random(seed, 4 * n + 2) * total;
        int chosen = lastAllowed;
        for (int i = 0; i < lastAllowed; i++) {
            if (allowed(h, base, r, i, children)) {
                float w = r.weights().get(i);
                if (u < w) {
                    chosen = i;
                    break;
                }
                u -= w;
            }
        }
        if (r.shuffled()) {
            h[base + 2 + chosen] = 1;
            h[base]++;
        } else if (r.noRepeat() > 0) {
            h[base + 2 + h[base + 1]] = chosen + 1; // 0 is no pick yet
            h[base + 1] = (h[base + 1] + 1) % r.noRepeat();
        }
        return chosen;
    }

    private static boolean allowed(int[] h, int base, Random r, int child, int children) {
        if (r.shuffled()) {
            return h[base + 2 + child] == 0;
        }
        for (int i = 0; i < r.noRepeat(); i++) {
            if (h[base + 2 + i] == child + 1) {
                return false;
            }
        }
        return true;
    }

    /** Voice {@code v}'s gain at the parameter values {@code values}: drawn, times its curves and blends. */
    static float gain(Plan plan, int v, Sound sound, float[] values) {
        float g = plan.gain[v];
        int end = plan.factorStart[v] + plan.factorCount[v];
        for (int f = plan.factorStart[v]; f < end; f++) {
            if (plan.factorKind[f] == VOLUME_CURVE) {
                Shaping.ParamCurve c = (Shaping.ParamCurve) plan.factorRef[f];
                g *= c.curve().at(values[sound.parameterIndex(c.parameter())]);
            } else if (plan.factorKind[f] == BLEND) {
                Blend b = (Blend) plan.factorRef[f];
                g *= b.gain(plan.factorArg[f], values[sound.parameterIndex(b.parameter())]);
            }
        }
        return g;
    }

    /** Voice {@code v}'s playback rate at the parameter values {@code values}: drawn, times its pitch curves. */
    static float rate(Plan plan, int v, Sound sound, float[] values) {
        float r = plan.rate[v];
        int end = plan.factorStart[v] + plan.factorCount[v];
        for (int f = plan.factorStart[v]; f < end; f++) {
            if (plan.factorKind[f] == PITCH_CURVE) {
                Shaping.ParamCurve c = (Shaping.ParamCurve) plan.factorRef[f];
                r *= c.curve().at(values[sound.parameterIndex(c.parameter())]);
            }
        }
        return r;
    }
}
