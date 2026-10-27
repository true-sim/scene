package com.truesim.scene.world;

/** How far from an interest source a layer is wanted, for {@link Detail#full()}; bands carry their own reach. */
public record Range(double radius) {

    public Range {
        if (!(radius > 0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException("A range needs a positive, finite radius: " + radius);
        }
    }

    /** Everything within {@code radius} of the source. */
    public static Range radius(double radius) {
        return new Range(radius);
    }
}
