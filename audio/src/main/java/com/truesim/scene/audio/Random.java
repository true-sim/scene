package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Plays one child, picked from the instance's seed by weight, avoiding the last {@code noRepeat} picks of this node
 * in its store; with {@code shuffle}, every child plays once before any repeats.
 *
 * @param children the children
 * @param weights  their weights, all 1 by default
 * @param noRepeat how many of the last picks are not picked again
 * @param shuffled whether all children play before one repeats
 * @param shaping  volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Random(List<SoundNode> children, List<Float> weights, int noRepeat, boolean shuffled, Shaping shaping)
        implements SoundNode {

    public Random {
        children = List.copyOf(children);
        weights = List.copyOf(weights);
        Objects.requireNonNull(shaping, "shaping");
    }

    /** One of {@code children}, with equal weights. */
    public static Random of(SoundNode... children) {
        return new Random(List.of(children), Collections.nCopies(children.length, 1f), 0, false, Shaping.NONE);
    }

    /** One of {@code clips}, with equal weights. */
    @SafeVarargs
    public static Random of(AssetRef<AudioClip>... clips) {
        SoundNode[] children = new SoundNode[clips.length];
        for (int i = 0; i < clips.length; i++) {
            children[i] = Play.clip(clips[i]);
        }
        return of(children);
    }

    /** A copy with the children weighted by {@code weights}, one each. */
    public Random weights(double... weights) {
        List<Float> list = new ArrayList<>();
        for (double w : weights) {
            list.add((float) w);
        }
        return new Random(children, list, noRepeat, shuffled, shaping);
    }

    /** A copy avoiding the last {@code picks} picks. */
    public Random noRepeat(int picks) {
        return new Random(children, weights, picks, shuffled, shaping);
    }

    /** A copy playing every child once before any repeats. */
    public Random shuffle() {
        return new Random(children, weights, noRepeat, true, shaping);
    }

    @Override
    public Random with(Shaping shaping) {
        return new Random(children, weights, noRepeat, shuffled, shaping);
    }
}
