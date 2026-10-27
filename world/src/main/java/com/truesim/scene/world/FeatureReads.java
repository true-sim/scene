package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/** The feature states one load sees, remembering which features it read: what its cell depends on. */
final class FeatureReads implements FeatureView {

    final FeatureState state;
    private long[] bits;

    @AllowAllocation("starting a load")
    FeatureReads(FeatureState state) {
        this.state = state;
        this.bits = new long[(Feature.count() + 63) >>> 6];
    }

    @Override
    public boolean isOn(Feature<?> feature) {
        record(feature);
        return state.isOn(feature);
    }

    @Override
    public <P> P value(Feature<P> feature) {
        record(feature);
        return state.value(feature);
    }

    @AllowAllocation("growing, in the background")
    private void record(Feature<?> feature) {
        if (feature.isLive()) {
            return;
        }
        int word = feature.id() >>> 6;
        if (word >= bits.length) {
            bits = Arrays.copyOf(bits, word + 1);
        }
        bits[word] |= 1L << feature.id();
    }

    /** The features read, as bits by feature id. */
    long[] bits() {
        return bits;
    }

    /** Whether any feature read is among {@code changed}. */
    static boolean intersects(long[] read, long[] changed) {
        for (int i = Math.min(read.length, changed.length) - 1; i >= 0; i--) {
            if ((read[i] & changed[i]) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether a feature read has changed between the state this load saw and {@code now}. */
    boolean outdated(FeatureState now) {
        return state != now && intersects(bits, FeatureState.changed(state, now));
    }
}
