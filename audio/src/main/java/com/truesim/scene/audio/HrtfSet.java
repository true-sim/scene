package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A set of head-related transfer functions (§2.9), already prepared: for each measured direction (a unit vector in
 * the listener's frame, x right, y up, -z ahead, and the distance it was measured at) a minimum-phase impulse
 * response per ear and each ear's delay (its onset, in samples at the set's rate, the common delay of the set taken
 * out), so the interaural time difference is the right delay minus the left. The engine resamples, triangulates and
 * convolves it; the runtime reads only this, never SOFA (decision 2: SOFA files are converted at build time).
 *
 * <p>The {@code .hrtf} format, little-endian: {@code "HRTF"}, version 1, rate, taps and direction count as ints, the
 * credit as an int length and UTF-8 bytes, then per direction x, y, z, distance, left delay and right delay as floats,
 * then per direction its left and then right filter, {@code taps} floats each. Immutable.
 */
public final class HrtfSet {

    /** The left ear's index. */
    public static final int LEFT = 0;
    /** The right ear's index. */
    public static final int RIGHT = 1;

    private static final int MAGIC = 'H' | 'R' << 8 | 'T' << 16 | 'F' << 24;
    private static final int VERSION = 1;

    private final int rate;
    private final int taps;
    private final int count;
    private final String credit;
    private final float[] directions;
    private final float[] distances;
    private final float[] delays;
    private final float[] filters;

    private HrtfSet(int rate, int taps, String credit, float[] directions, float[] distances, float[] delays,
            float[] filters) {
        this.rate = rate;
        this.taps = taps;
        this.count = distances.length;
        this.credit = credit;
        this.directions = directions;
        this.distances = distances;
        this.delays = delays;
        this.filters = filters;
    }

    /**
     * A set at {@code rate} of filters {@code taps} long, crediting {@code credit} (its source and licence): per
     * direction three floats of {@code directions} (normalised here), one of {@code distances} (metres), two of
     * {@code delays} (left, right; samples) and {@code 2 × taps} of {@code filters} (left, then right). The arrays are
     * copied. Fails with every problem found.
     */
    @AllowAllocation("making a set")
    public static HrtfSet of(int rate, int taps, String credit, float[] directions, float[] distances, float[] delays,
            float[] filters) {
        Objects.requireNonNull(credit, "credit");
        int n = distances.length;
        List<String> problems = new ArrayList<>();
        if (rate <= 0) {
            problems.add("its rate is " + rate + " Hz: it must be positive");
        }
        if (taps <= 0) {
            problems.add("its filters are " + taps + " taps long: they must have at least one");
        }
        if (n == 0) {
            problems.add("it has no directions");
        }
        if (directions.length != 3 * n || delays.length != 2 * n || filters.length != 2L * n * Math.max(taps, 0)) {
            problems.add("its arrays do not match " + n + " directions of " + taps + " taps: " + directions.length
                + " direction floats, " + delays.length + " delays, " + filters.length + " filter floats");
        }
        float[] unit = directions.clone();
        for (int i = 0; i < Math.min(n, directions.length / 3); i++) {
            double x = unit[3 * i];
            double y = unit[3 * i + 1];
            double z = unit[3 * i + 2];
            double length = Math.sqrt(x * x + y * y + z * z);
            if (!(length > 1e-6) || !(distances[i] > 0)) {
                problems.add("direction " + i + " has no direction or no distance");
                continue;
            }
            unit[3 * i] = (float) (x / length);
            unit[3 * i + 1] = (float) (y / length);
            unit[3 * i + 2] = (float) (z / length);
        }
        InvalidAudioException.throwIf("HRTF set", problems);
        return new HrtfSet(rate, taps, credit, unit, distances.clone(), delays.clone(), filters.clone());
    }

    /** The sample rate its filters and delays are at. */
    public int rate() {
        return rate;
    }

    /** Taps per filter. */
    public int taps() {
        return taps;
    }

    /** Measured directions. */
    public int count() {
        return count;
    }

    /** Where it comes from and under which licence: to be shown with the game's credits. */
    public String credit() {
        return credit;
    }

    /** Component {@code axis} (0 x right, 1 y up, 2 z back) of direction {@code i}'s unit vector. */
    public float direction(int i, int axis) {
        return directions[3 * i + axis];
    }

    /** The distance direction {@code i} was measured at, metres. */
    public float distance(int i) {
        return distances[i];
    }

    /** Ear {@code ear}'s delay for direction {@code i}, samples at {@link #rate()}. */
    public float delay(int i, int ear) {
        return delays[2 * i + ear];
    }

    /** Tap {@code t} of ear {@code ear}'s minimum-phase filter for direction {@code i}. */
    public float tap(int i, int ear, int t) {
        return filters[(2 * i + ear) * taps + t];
    }

    /** Copies ear {@code ear}'s filter for direction {@code i} into {@code out} from {@code offset}. */
    public void filter(int i, int ear, float[] out, int offset) {
        System.arraycopy(filters, (2 * i + ear) * taps, out, offset, taps);
    }

    /** Bytes it holds, for the asset cache's budget. */
    public long bytes() {
        return 4L * (directions.length + distances.length + delays.length + filters.length);
    }

    /** Writes it in the {@code .hrtf} format to {@code out}; does not close it. */
    @AllowAllocation("writing a set")
    public void write(OutputStream out) throws IOException {
        byte[] name = credit.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(24 + name.length + 24 * count + 4 * filters.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(VERSION).putInt(rate).putInt(taps).putInt(count).putInt(name.length).put(name);
        for (int i = 0; i < count; i++) {
            b.putFloat(directions[3 * i]).putFloat(directions[3 * i + 1]).putFloat(directions[3 * i + 2])
                .putFloat(distances[i]).putFloat(delays[2 * i]).putFloat(delays[2 * i + 1]);
        }
        for (float f : filters) {
            b.putFloat(f);
        }
        out.write(b.array());
    }

    /** Reads a set in the {@code .hrtf} format from {@code in}; does not close it. */
    @AllowAllocation("loading a set")
    public static HrtfSet read(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 24 || b.getInt() != MAGIC) {
            throw new IOException("Not an HRTF set (no HRTF header)");
        }
        int version = b.getInt();
        if (version != VERSION) {
            throw new IOException("An HRTF set of version " + version + "; this reads version " + VERSION);
        }
        int rate = b.getInt();
        int taps = b.getInt();
        int count = b.getInt();
        int nameLength = b.getInt();
        long need = 24L + nameLength + 24L * count + 8L * count * taps;
        if (nameLength < 0 || count < 0 || taps < 0 || need != bytes.length) {
            throw new IOException("The HRTF set is " + bytes.length + " bytes; its header says " + need);
        }
        String credit = new String(bytes, 24, nameLength, StandardCharsets.UTF_8);
        b.position(24 + nameLength);
        float[] directions = new float[3 * count];
        float[] distances = new float[count];
        float[] delays = new float[2 * count];
        for (int i = 0; i < count; i++) {
            directions[3 * i] = b.getFloat();
            directions[3 * i + 1] = b.getFloat();
            directions[3 * i + 2] = b.getFloat();
            distances[i] = b.getFloat();
            delays[2 * i] = b.getFloat();
            delays[2 * i + 1] = b.getFloat();
        }
        float[] filters = new float[2 * count * taps];
        b.asFloatBuffer().get(filters);
        return of(rate, taps, credit, directions, distances, delays, filters);
    }
}
