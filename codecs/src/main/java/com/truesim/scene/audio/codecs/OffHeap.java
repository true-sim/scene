package com.truesim.scene.audio.codecs;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Encoded bytes held off-heap for streamed clips, freed once their owner is unreachable. */
@com.truesim.annotations.AllowAllocation("loading a clip")
final class OffHeap {

    private static final Cleaner CLEANER = Cleaner.create();

    private OffHeap() {
    }

    /** Frees {@code data} once {@code owner} is unreachable. */
    static void freeWith(Object owner, ByteBuffer data) {
        CLEANER.register(owner, free(data));
    }

    /** The action freeing {@code data}. */
    static Runnable free(ByteBuffer data) {
        return () -> MemoryUtil.memFree(data);
    }
}
