package com.truesim.scene.audio;

/**
 * Reads a clip's samples in order, as floats, from where it was sought: the streaming thread's view of a clip (§2.14).
 * Decoded clips read their memory; streamed ones decode (Vorbis in {@code scene-audio-codecs}). Not thread-safe: one
 * per stream, used by one thread at a time; reading allocates nothing.
 */
public interface ClipDecoder extends AutoCloseable {

    /** Reads up to {@code frames} interleaved frames into {@code out} from frame {@code offset}; 0 at the end. */
    int read(float[] out, int offset, int frames);

    /** Moves to {@code frame}, exactly. */
    void seek(long frame);

    @Override
    void close();
}
