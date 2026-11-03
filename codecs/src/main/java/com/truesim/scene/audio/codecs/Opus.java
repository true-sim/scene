package com.truesim.scene.audio.codecs;

import static org.lwjgl.util.opus.OpusFile.nop_read_float;
import static org.lwjgl.util.opus.OpusFile.op_channel_count;
import static org.lwjgl.util.opus.OpusFile.op_free;
import static org.lwjgl.util.opus.OpusFile.op_open_memory;
import static org.lwjgl.util.opus.OpusFile.op_pcm_seek;
import static org.lwjgl.util.opus.OpusFile.op_pcm_total;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.ClipDecoder;
import com.truesim.scene.audio.Decibels;
import com.truesim.scene.audio.Loudness;
import com.truesim.scene.audio.WavReader;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Ogg Opus through libopusfile (AU5, §2.14): always 48 kHz, the pre-skip and end trim applied by the library, seeks
 * exact to the sample ({@code op_pcm_seek}). Decoded whole or streamed as Vorbis is (over 10 s or 1 MB decoded, or
 * {@code #stream}); measured for loudness at import. Each stream opens its own state on the shared encoded bytes.
 */
@AllowAllocation("loading a clip")
public final class Opus {

    private static final Cleaner CLEANER = Cleaner.create();
    private static final int RATE = 48_000;

    private Opus() {
    }

    /** The clip of Ogg Opus {@code bytes}: decoded whole, or streamed when {@code stream} says so or when large. */
    public static AudioClip read(byte[] bytes, Boolean stream) throws IOException {
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        long handle = handle(data);
        if (handle == 0) {
            MemoryUtil.memFree(data);
            throw new IOException("Not an Ogg Opus file");
        }
        int channels = op_channel_count(handle, -1);
        long frames = op_pcm_total(handle, -1);
        boolean streamed = stream != null ? stream
            : frames > Vorbis.STREAM_SECONDS * RATE || frames * channels * 4 > Vorbis.STREAM_BYTES;
        AudioClip.Builder clip = AudioClip.builder(RATE, channels);
        Decoder decoder = new Decoder(handle, channels);
        Loudness meter = new Loudness(RATE, channels);
        float[] samples = new float[streamed ? 4096 * channels : (int) (frames * channels)];
        int done = 0;
        for (int n; (n = decoder.read(samples, streamed ? 0 : done, streamed ? 4096 : (int) frames - done)) > 0; ) {
            if (streamed) {
                meter.add(samples, n);
            } else {
                done += n;
            }
        }
        decoder.close();
        if (!streamed) {
            MemoryUtil.memFree(data);
            return clip.floats(samples).measure(WavReader.TARGET).build();
        }
        double lufs = meter.integrated();
        clip.loudness((float) lufs, lufs == Double.NEGATIVE_INFINITY ? 1
            : Decibels.gain((float) (WavReader.TARGET - lufs)));
        Encoded encoded = new Encoded(data, channels);
        return clip.streamed(frames, encoded::open, bytes.length).build();
    }

    private static long handle(ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return op_open_memory(data, stack.mallocInt(1));
        }
    }

    /** A streamed clip's encoded bytes, off-heap, freed once the clip is unreachable. */
    private static final class Encoded {
        private final ByteBuffer data;
        private final int channels;

        Encoded(ByteBuffer data, int channels) {
            this.data = data;
            this.channels = channels;
            CLEANER.register(this, () -> MemoryUtil.memFree(data));
        }

        ClipDecoder open() {
            return new Decoder(handle(data.duplicate()), channels); // they decoded when the clip was read
        }
    }

    /** One stream's decoder: its own libopusfile state; reading allocates nothing. */
    private static final class Decoder implements ClipDecoder {
        private final long handle;
        private final int channels;
        private final FloatBuffer buffer;

        Decoder(long handle, int channels) {
            this.handle = handle;
            this.channels = channels;
            buffer = MemoryUtil.memAllocFloat(5_760 * channels); // 120 ms: the most one packet decodes to
        }

        @Override
        public int read(float[] out, int offset, int frames) {
            int want = Math.min(frames, buffer.capacity() / channels);
            int n = Math.max(0, nop_read_float(handle, MemoryUtil.memAddress(buffer), want * channels, 0));
            buffer.get(0, out, offset * channels, n * channels);
            return n;
        }

        @Override
        public void seek(long frame) {
            op_pcm_seek(handle, frame);
        }

        @Override
        public void close() {
            op_free(handle);
            MemoryUtil.memFree(buffer);
        }
    }
}
