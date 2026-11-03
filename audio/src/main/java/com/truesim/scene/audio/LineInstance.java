package com.truesim.scene.audio;

/**
 * A line said ({@link Dialogue#say}): queued, playing, or done (inert then). Its lip-sync hooks read at the time the
 * line is heard: the viseme weights, or its amplitude for a jaw.
 */
public final class LineInstance {

    private final Dialogue dialogue;
    private final Dialogue.Entry entry;

    LineInstance(Dialogue dialogue, Dialogue.Entry entry) {
        this.dialogue = dialogue;
        this.entry = entry;
    }

    /** Its line. */
    public Line line() {
        return entry.line;
    }

    /** Whether it is queued or playing. */
    public boolean alive() {
        return entry.alive;
    }

    /** The sound instance playing it; 0 while queued or once dropped. */
    public long instance() {
        return entry.instance;
    }

    /** Seconds into it the listener hears now; -1 before it is heard or when not playing. */
    public double heard() {
        return entry.alive ? dialogue.heardOf(entry) : -1;
    }

    /**
     * Fills {@code weights} (9, {@link Visemes#SHAPES}) with its mouth shapes at the heard time: rest when it has no
     * track or is not heard. Allocates nothing.
     */
    public void visemes(float[] weights) {
        double h = heard();
        if (entry.line.visemes() != null) {
            entry.line.visemes().weights(h, weights);
        } else {
            java.util.Arrays.fill(weights, 0, Visemes.SHAPES.length(), 0);
            weights[Visemes.SHAPES.length() - 1] = 1;
        }
    }

    /** Its smoothed amplitude now, from the audio thread (a jaw-only fallback); 0 without an engine. */
    public float envelope() {
        return entry.alive ? entry.envelope : 0;
    }
}
