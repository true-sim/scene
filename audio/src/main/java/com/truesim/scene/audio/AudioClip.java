package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Samples, decoded: interleaved 16-bit or float PCM in native memory, with the rate, channels, loop points, markers and
 * the gain to a target loudness. Immutable. The asset cache closes clips it evicts (through
 * {@link com.truesim.scene.assets.AssetLoader#unload}); close clips built by hand once nothing plays them.
 *
 * <pre>{@code
 * AssetRef<AudioClip> KNOCK = paths.ref(AudioAssets.CLIP, "game://sfx/knock.wav");
 * AudioClip tone = AudioClip.builder(48_000, 1).floats(samples).loop(0, 480).build();
 * }</pre>
 *
 * <p>Reading samples ({@link #sample}) allocates nothing and may happen on any thread until the clip is closed.
 */
public final class AudioClip implements AutoCloseable {

    /** How samples are stored. */
    public enum Encoding {
        /** Signed 16-bit integers. */
        PCM16(2),
        /** 32-bit floats in [-1, 1]. */
        FLOAT(4);

        private final int bytes;

        Encoding(int bytes) {
            this.bytes = bytes;
        }

        /** Bytes a sample takes. */
        public int bytes() {
            return bytes;
        }
    }

    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;

    private final int rate;
    private final int channels;
    private final long frames;
    private final Encoding encoding;
    private final long loopStart;
    private final long loopEnd;
    private final List<SoundMarker> markers;
    private final float loudness;
    private final float gain;
    private final Arena arena;
    private final MemorySegment samples;
    private final AtomicBoolean closed = new AtomicBoolean();

    @AllowAllocation("loading a clip")
    private AudioClip(Builder b) {
        rate = b.rate;
        channels = b.channels;
        encoding = b.encoding;
        frames = b.count / channels;
        loopStart = b.loopStart;
        loopEnd = b.loopEnd;
        markers = List.copyOf(b.markers);
        loudness = b.loudness;
        gain = b.gain;
        arena = Arena.ofShared();
        samples = arena.allocate(Math.max(1, (long) b.count * encoding.bytes), 4);
        if (encoding == Encoding.PCM16) {
            MemorySegment.copy(b.shorts, 0, samples, SHORT, 0, b.count);
        } else {
            MemorySegment.copy(b.floats, 0, samples, FLOAT, 0, b.count);
        }
    }

    /** A clip of {@code channels} channels at {@code rate} frames a second, to fill with samples. */
    @AllowAllocation("loading a clip")
    public static Builder builder(int rate, int channels) {
        return new Builder(rate, channels);
    }

    /** Frames a second. */
    public int rate() {
        return rate;
    }

    public int channels() {
        return channels;
    }

    /** How many frames it holds (a frame is one sample per channel). */
    public long frames() {
        return frames;
    }

    /** How long it plays at its rate, in seconds. */
    public double seconds() {
        return (double) frames / rate;
    }

    public Encoding encoding() {
        return encoding;
    }

    /** Whether it has a loop region of its own ({@link #loopStart()} to {@link #loopEnd()}). */
    public boolean looped() {
        return loopEnd > 0;
    }

    /** The first frame of its loop region; 0 without one. */
    public long loopStart() {
        return loopStart;
    }

    /** The frame after its loop region; {@link #frames()} without one, so a looping sound loops the whole clip. */
    public long loopEnd() {
        return loopEnd > 0 ? loopEnd : frames;
    }

    /** Its markers, by frame. */
    public List<SoundMarker> markers() {
        return markers;
    }

    /** Its integrated loudness in LUFS; NaN when not measured. */
    public float loudness() {
        return loudness;
    }

    /** The linear gain bringing it to its target loudness; 1 when not measured. */
    public float gain() {
        return gain;
    }

    /** Whether it streams from encoded bytes instead of being decoded whole (none do yet: WAV decodes whole). */
    public boolean streamed() {
        return false;
    }

    /** Sample {@code channel} of {@code frame}, in [-1, 1]. */
    public float sample(long frame, int channel) {
        long index = frame * channels + channel;
        return encoding == Encoding.PCM16 ? samples.getAtIndex(SHORT, index) * 0x1p-15f
            : samples.getAtIndex(FLOAT, index);
    }

    /** The samples, interleaved, as {@link #encoding()} says; valid until the clip is closed. */
    public MemorySegment segment() {
        return samples;
    }

    /** Memory it takes, for the asset cache's budget. */
    public long bytes() {
        return samples.byteSize();
    }

    /** Frees the samples: reading them throws from now on. Closing twice does nothing. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            arena.close();
        }
    }

    @Override
    @AllowAllocation("formatting")
    public String toString() {
        return "audio clip " + channels + " ch " + rate + " Hz " + frames + " frames " + encoding;
    }

    /** Builds a clip: samples once, loop points, markers and loudness optionally. */
    @AllowAllocation("loading a clip")
    public static final class Builder {

        private final int rate;
        private final int channels;
        private Encoding encoding;
        private short[] shorts;
        private float[] floats;
        private int count;
        private long loopStart;
        private long loopEnd;
        private final List<SoundMarker> markers = new ArrayList<>();
        private float loudness = Float.NaN;
        private float gain = 1;

        private Builder(int rate, int channels) {
            if (rate <= 0) {
                throw new IllegalArgumentException("Not a sample rate: " + rate);
            }
            if (channels < 1 || channels > 8) {
                throw new IllegalArgumentException("Clips have 1 to 8 channels, not " + channels);
            }
            this.rate = rate;
            this.channels = channels;
        }

        /** Its samples as 16-bit integers, interleaved. */
        public Builder pcm16(short[] interleaved) {
            samples(interleaved.length);
            encoding = Encoding.PCM16;
            shorts = interleaved;
            return this;
        }

        /** Its samples as floats in [-1, 1], interleaved. */
        public Builder floats(float[] interleaved) {
            samples(interleaved.length);
            encoding = Encoding.FLOAT;
            floats = interleaved;
            return this;
        }

        private void samples(int length) {
            if (length % channels != 0) {
                throw new IllegalArgumentException(length + " samples are no whole number of " + channels
                    + "-channel frames");
            }
            count = length;
        }

        /** Loops frames {@code start} (inclusive) to {@code end} (exclusive) when the sound loops. */
        public Builder loop(long start, long end) {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("Not a loop region: " + start + " to " + end);
            }
            loopStart = start;
            loopEnd = end;
            return this;
        }

        /** Marks {@code frame} as {@code name}. */
        public Builder marker(String name, long frame) {
            markers.add(new SoundMarker(name, frame));
            return this;
        }

        /** Its measured loudness in LUFS and the gain to the target loudness. */
        public Builder loudness(float lufs, float gainToTarget) {
            loudness = lufs;
            gain = gainToTarget;
            return this;
        }

        /** The clip; throws if it has no samples, or loop points or markers past its end. */
        public AudioClip build() {
            Objects.requireNonNull(encoding, "samples");
            long frames = count / channels;
            if (loopEnd > frames) {
                throw new IllegalArgumentException("The loop ends past the clip: " + loopEnd + " > " + frames);
            }
            markers.sort(Comparator.comparingLong(SoundMarker::frame));
            if (!markers.isEmpty() && markers.getLast().frame() > frames) {
                throw new IllegalArgumentException("A marker past the clip: " + markers.getLast());
            }
            return new AudioClip(this);
        }
    }
}
