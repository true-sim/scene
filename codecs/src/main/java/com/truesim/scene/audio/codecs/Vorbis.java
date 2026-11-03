package com.truesim.scene.audio.codecs;

import static org.lwjgl.stb.STBVorbis.nstb_vorbis_get_samples_float_interleaved;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_close;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_get_comment;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_get_info;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_open_memory;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_seek;
import static org.lwjgl.stb.STBVorbis.stb_vorbis_stream_length_in_samples;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.ClipDecoder;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBVorbisComment;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Ogg Vorbis through stb_vorbis (AU5): a file's rate, channels, length and loop comments ({@code LOOPSTART} and
 * {@code LOOPLENGTH} or {@code LOOPEND}, in frames, as RPG Maker and many tools write them), decoded whole into a clip
 * or kept encoded for streaming, with decoders that seek exactly. The encoded bytes live off-heap as long as the clip;
 * each stream's decoder has its own stb_vorbis state.
 */
@AllowAllocation("loading a clip")
public final class Vorbis {

    /** Clips longer than this many seconds stream unless asked otherwise (§2.14). */
    public static final double STREAM_SECONDS = 10;
    /** Clips larger than this decoded stream unless asked otherwise. */
    public static final long STREAM_BYTES = 1 << 20;

    private Vorbis() {
    }

    /**
     * The clip of Ogg Vorbis {@code bytes}: decoded whole, or streamed when {@code stream} says so, or when null and
     * the clip is longer than {@value #STREAM_SECONDS} s or larger than 1 MB decoded.
     */
    public static AudioClip read(byte[] bytes, Boolean stream) throws IOException {
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        long handle = open(data);
        int[] info = info(handle);
        {
            int rate = info[0];
            int channels = info[1];
            long frames = stb_vorbis_stream_length_in_samples(handle);
            long[] loop = loop(handle, frames);
            boolean streamed = stream != null ? stream
                : frames > STREAM_SECONDS * rate || frames * channels * 4 > STREAM_BYTES;
            AudioClip.Builder clip = AudioClip.builder(rate, channels);
            if (loop[1] > loop[0]) {
                clip.loop(loop[0], loop[1]);
            }
            if (streamed) {
                stb_vorbis_close(handle);
                Encoded encoded = new Encoded(data, channels);
                return clip.streamed(frames, encoded::open, bytes.length).build();
            }
            float[] samples = new float[(int) (frames * channels)];
            Decoder decoder = new Decoder(handle, channels);
            int done = 0;
            for (int n; (n = decoder.read(samples, done, (int) frames - done)) > 0; ) {
                done += n;
            }
            stb_vorbis_close(handle);
            MemoryUtil.memFree(data);
            return clip.floats(samples).build();
        }
    }

    /** The rate and channels of the stream at {@code handle}. */
    private static int[] info(long handle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            STBVorbisInfo info = stb_vorbis_get_info(handle, STBVorbisInfo.malloc(stack));
            return new int[] {info.sample_rate(), info.channels()};
        }
    }

    private static long open(ByteBuffer data) throws IOException {
        long handle = handle(data);
        if (handle == 0) {
            MemoryUtil.memFree(data);
            throw new IOException("Not an Ogg Vorbis file");
        }
        return handle;
    }

    /** stb_vorbis's state over {@code data}, or 0 if it is no Vorbis. */
    private static long handle(ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return stb_vorbis_open_memory(data, stack.mallocInt(1), null);
        }
    }

    /** The loop region its comments give, in frames ([0, 0] for none). */
    private static long[] loop(long handle, long frames) {
        long start = -1;
        long length = -1;
        long end = -1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            STBVorbisComment comment = stb_vorbis_get_comment(handle, STBVorbisComment.malloc(stack));
            PointerBuffer list = comment.comment_list();
            for (int i = 0; i < comment.comment_list_length(); i++) {
                String text = MemoryUtil.memUTF8(list.get(i));
                int eq = text.indexOf('=');
                String key = text.substring(0, Math.max(0, eq)).toUpperCase(Locale.ROOT);
                long value = number(text.substring(eq + 1));
                switch (key) {
                    case "LOOPSTART" -> start = value;
                    case "LOOPLENGTH" -> length = value;
                    case "LOOPEND" -> end = value;
                    default -> {
                        // other tags: title, artist
                    }
                }
            }
        }
        if (start < 0) {
            return new long[] {0, 0};
        }
        long stop = length > 0 ? start + length : end > start ? end : frames;
        return new long[] {start, Math.min(stop, frames)};
    }

    private static long number(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final Cleaner CLEANER = Cleaner.create();

    /** A streamed clip's encoded bytes, off-heap, opened by each stream; freed once the clip is unreachable. */
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

    /** One stream's decoder: its own stb_vorbis state over the shared bytes; reading allocates nothing. */
    private static final class Decoder implements ClipDecoder {
        private final long handle;
        private final int channels;
        private final float[] buffer = new float[4096];

        Decoder(long handle, int channels) {
            this.handle = handle;
            this.channels = channels;
        }

        @Override
        public int read(float[] out, int offset, int frames) {
            int want = Math.min(frames, buffer.length / channels);
            int n = nstb_vorbis_get_samples_float_interleaved(handle, channels, buffer, want * channels);
            System.arraycopy(buffer, 0, out, offset * channels, n * channels);
            return n;
        }

        @Override
        public void seek(long frame) {
            stb_vorbis_seek(handle, (int) frame);
        }

        @Override
        public void close() {
            stb_vorbis_close(handle);
        }
    }
}
