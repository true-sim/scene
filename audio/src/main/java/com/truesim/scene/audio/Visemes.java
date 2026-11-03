package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A line's viseme track (§2.15): mouth shapes over time, from Rhubarb Lip Sync's JSON output (its {@code mouthCues}).
 * The nine shapes are Rhubarb's: A (closed: M, B, P) to H (L), and X (rest); {@link #weights} gives each shape's weight
 * at a time, cross-fading from the shape before over the first 40 ms of each cue.
 */
public final class Visemes {

    /** The shapes, in the order of {@link #weights}' output. */
    public static final String SHAPES = "ABCDEFGHX";
    /** Seconds a shape cross-fades from the one before. */
    public static final double FADE = 0.04;
    private static final Pattern CUE = Pattern.compile(
        "\\{\\s*\"start\"\\s*:\\s*([0-9.]+)\\s*,\\s*\"end\"\\s*:\\s*([0-9.]+)\\s*,\\s*\"value\"\\s*:\\s*\"([A-Z])\"\\s*}");

    private final double[] starts;
    private final double[] ends;
    private final int[] shapes;

    @AllowAllocation("loading a line")
    private Visemes(List<double[]> cues) {
        starts = new double[cues.size()];
        ends = new double[cues.size()];
        shapes = new int[cues.size()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = cues.get(i)[0];
            ends[i] = cues.get(i)[1];
            shapes[i] = (int) cues.get(i)[2];
        }
    }

    /** The track in Rhubarb Lip Sync JSON {@code json}. */
    @AllowAllocation("loading a line")
    public static Visemes rhubarb(String json) {
        List<double[]> cues = new ArrayList<>();
        Matcher m = CUE.matcher(json);
        while (m.find()) {
            int shape = SHAPES.indexOf(m.group(3));
            if (shape < 0) {
                throw new IllegalArgumentException("Not a Rhubarb mouth shape: " + m.group(3));
            }
            cues.add(new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), shape});
        }
        if (cues.isEmpty()) {
            throw new IllegalArgumentException("No mouthCues in this Rhubarb output");
        }
        return new Visemes(cues);
    }

    /** How many cues it has. */
    public int size() {
        return starts.length;
    }

    /**
     * Each shape's weight at {@code seconds} into the line into {@code out} (at least 9 long, in {@link #SHAPES}
     * order): the shape of the cue there, faded in over {@link #FADE} from the cue before; rest (X) outside the cues.
     * Allocates nothing.
     */
    public void weights(double seconds, float[] out) {
        java.util.Arrays.fill(out, 0, SHAPES.length(), 0);
        int i = 0;
        while (i < starts.length && ends[i] <= seconds) {
            i++;
        }
        if (i == starts.length || seconds < starts[i]) {
            out[SHAPES.length() - 1] = 1;
            return;
        }
        float in = (float) Math.min(1, (seconds - starts[i]) / FADE);
        int before = i == 0 ? SHAPES.length() - 1 : shapes[i - 1];
        out[before] += 1 - in;
        out[shapes[i]] += in;
    }
}
