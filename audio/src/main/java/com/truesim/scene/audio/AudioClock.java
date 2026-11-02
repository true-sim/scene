package com.truesim.scene.audio;

/**
 * The audio frame counter (AU8), as the game thread sees it: music and anything timed is scheduled in frames of the
 * output, a fixed latency ahead, so it starts on its exact sample. The engine's clock is published by the audio thread
 * through a seqlock; tests use a fake one. Reading allocates nothing.
 */
public interface AudioClock {

    /** Output frames a second. */
    int rate();

    /** Frames rendered so far: the frame the renderer works on next. */
    long renderedFrame();

    /** The frame reaching the listener's ears now: rendered, minus what waits in the queue and the device. */
    long heardFrame();

    /** The earliest frame a command written now is sure to start at: rendered plus the scheduling latency. */
    long schedulePoint();
}
