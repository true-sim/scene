package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * What a kind of observer needs from the world: for each layer, the {@link Detail} and, for full detail, the
 * {@link Range}. Profiles are immutable and shared, e.g. as constants; each camera, viewport or player is an
 * {@link InterestSource} using one.
 *
 * <pre>{@code
 * static final InterestProfile PLAYER_VIEW = InterestProfile.builder()
 *     .layer(TERRAIN, Detail.bands(256, 1024, 4096))
 *     .layer(PROPS, Detail.bands(150, 600, 2500))
 *     .layer(COLLISION, Detail.full(), Range.radius(200))
 *     .build();
 *
 * static final InterestProfile SERVER_PLAYER = InterestProfile.builder()
 *     .layer(COLLISION, Detail.full(), Range.radius(300))   // physics only, nothing rendered
 *     .build();
 * }</pre>
 */
public final class InterestProfile {

    /** One layer's entry. */
    public record Entry(Layer<?> layer, Detail detail, Range range) {
    }

    private final List<Entry> entries;

    private InterestProfile(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    @AllowAllocation("set up once")
    public static Builder builder() {
        return new Builder();
    }

    /** The layers and how much of each, in the order added. */
    public List<Entry> entries() {
        return entries;
    }

    /** Builds a profile. */
    public static final class Builder {

        private final List<Entry> entries = new ArrayList<>();

        private Builder() {
        }

        /** Wants {@code layer} at bands of detail; full detail and screen-space error need a range (other overload). */
        public Builder layer(Layer<?> layer, Detail detail) {
            if (detail.isFull() || detail.isScreenError()) {
                throw new IllegalArgumentException((detail.isFull() ? "Full detail" : "Screen-space error") + " of "
                    + layer + " needs a range");
            }
            return add(layer, detail, null);
        }

        /** Wants {@code layer} at full detail, or as fine as its screen-space error needs, within {@code range}. */
        public Builder layer(Layer<?> layer, Detail detail, Range range) {
            if (!detail.isFull() && !detail.isScreenError()) {
                throw new IllegalArgumentException("Bands of " + layer + " carry their own range");
            }
            return add(layer, detail, range);
        }

        @AllowAllocation("set up once")
        private Builder add(Layer<?> layer, Detail detail, Range range) {
            if (detail.bandCount() > layer.levels().count()) {
                throw new IllegalArgumentException(detail + " needs " + detail.bandCount() + " levels; " + layer
                    + " has " + layer.levels().count());
            }
            for (Entry entry : entries) {
                if (entry.layer() == layer) {
                    throw new IllegalArgumentException(layer + " is already in this profile");
                }
            }
            entries.add(new Entry(layer, detail, range));
            return this;
        }

        public InterestProfile build() {
            return new InterestProfile(entries);
        }
    }
}
