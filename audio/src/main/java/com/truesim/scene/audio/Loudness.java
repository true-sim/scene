package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.Arrays;

/**
 * Integrated loudness after ITU-R BS.1770-4 (EBU R128): K-weighting (the high shelf and the RLB high-pass, designed
 * for any rate as libebur128 does), mean squares over 400 ms blocks overlapping by 75 %, channel weights (1 for the
 * front channels, 1.41 for the surrounds, none for the LFE), an absolute gate at −70 LUFS and a relative gate 10 LU
 * below the absolutely gated loudness. Fed in pieces ({@link #add}); for importers, not the audio thread.
 *
 * <pre>{@code
 * Loudness meter = new Loudness(48_000, 2);
 * meter.add(samples, frames);
 * double lufs = meter.integrated();
 * }</pre>
 */
@AllowAllocation("measuring at import")
public final class Loudness {

    private final int channels;
    private final double[] weights;
    private final double[] shelfB = new double[3];
    private final double[] shelfA = new double[3];
    private final double[] passA = new double[3];
    private final double[][] state;
    private final int step;
    private final double[] squares;
    private int filled;
    private double[] blocks = new double[64];
    private int count;
    private final double[][] quarters;
    private int quarter;

    /** A meter for {@code channels} channels at {@code rate}. */
    public Loudness(int rate, int channels) {
        this.channels = channels;
        weights = new double[channels];
        for (int c = 0; c < channels; c++) {
            boolean lfe = c == 3 && channels >= 6;
            boolean surround = c >= 4 && channels >= 6;
            weights[c] = lfe ? 0 : surround ? 1.41 : 1;
        }
        double k = Maths.tan(Math.PI * 1681.974450955533 / rate);
        double vh = Maths.pow(10, 3.999843853973347 / 20);
        double vb = Maths.pow(vh, 0.4996667741545416);
        double q = 0.7071752369554196;
        double a0 = 1 + k / q + k * k;
        shelfB[0] = (vh + vb * k / q + k * k) / a0;
        shelfB[1] = 2 * (k * k - vh) / a0;
        shelfB[2] = (vh - vb * k / q + k * k) / a0;
        shelfA[1] = 2 * (k * k - 1) / a0;
        shelfA[2] = (1 - k / q + k * k) / a0;
        k = Maths.tan(Math.PI * 38.13547087602444 / rate);
        q = 0.5003270373238773;
        a0 = 1 + k / q + k * k;
        passA[1] = 2 * (k * k - 1) / a0;
        passA[2] = (1 - k / q + k * k) / a0;
        state = new double[channels][8];
        step = Math.max(1, rate / 10); // 100 ms: a quarter of a block
        squares = new double[channels];
        quarters = new double[4][channels];
    }

    /** Adds {@code frames} interleaved frames of {@code samples}. */
    public void add(float[] samples, int frames) {
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                double[] s = state[c];
                double x = samples[f * channels + c];
                double y = shelfB[0] * x + shelfB[1] * s[0] + shelfB[2] * s[1] - shelfA[1] * s[2] - shelfA[2] * s[3];
                s[1] = s[0];
                s[0] = x;
                s[3] = s[2];
                s[2] = y;
                double z = y - 2 * s[4] + s[5] - passA[1] * s[6] - passA[2] * s[7];
                s[5] = s[4];
                s[4] = y;
                s[7] = s[6];
                s[6] = z;
                squares[c] += z * z;
            }
            if (++filled == step) {
                endQuarter();
            }
        }
    }

    /** A 100 ms quarter ended: with the three before it, a 400 ms block. */
    private void endQuarter() {
        System.arraycopy(squares, 0, quarters[quarter % 4], 0, channels);
        Arrays.fill(squares, 0);
        filled = 0;
        quarter++;
        if (quarter >= 4) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                double mean = 0;
                for (double[] q : quarters) {
                    mean += q[c];
                }
                sum += weights[c] * mean / (4.0 * step);
            }
            if (count == blocks.length) {
                blocks = Arrays.copyOf(blocks, 2 * count);
            }
            blocks[count++] = sum;
        }
    }

    /** The integrated loudness of what was added, LUFS; negative infinity for silence or less than 400 ms. */
    public double integrated() {
        double gated = gate(Maths.pow(10, (-70 + 0.691) / 10));
        return gated <= 0 ? Double.NEGATIVE_INFINITY : lufs(gate(gated * 0.1)); // the relative gate: 10 LU below
    }

    /** The mean of the blocks louder than {@code threshold} (a mean square), or 0 when none is. */
    private double gate(double threshold) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (blocks[i] > threshold) {
                sum += blocks[i];
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double lufs(double meanSquare) {
        return -0.691 + 10 * Maths.log(meanSquare) / Maths.log(10);
    }
}
