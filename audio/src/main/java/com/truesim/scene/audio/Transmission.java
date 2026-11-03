package com.truesim.scene.audio;

/**
 * How much of a sound gets through what lies between it and a listener (§2.11), per band: low (below about 250 Hz),
 * mid and high (above about 4 kHz), each a linear gain from 0 to 1. Filled by an {@link AudioWorld}'s trace; mutable,
 * one per ray, reused.
 */
public final class Transmission {

    private float low = 1;
    private float mid = 1;
    private float high = 1;

    /** Everything through: 1 in every band. */
    public Transmission open() {
        low = 1;
        mid = 1;
        high = 1;
        return this;
    }

    /** Multiplies in what one crossing lets through. */
    public Transmission cross(float lowGain, float midGain, float highGain) {
        low *= lowGain;
        mid *= midGain;
        high *= highGain;
        return this;
    }

    /** Multiplies in {@code material}'s per-crossing transmission. */
    public Transmission cross(AudioMaterial material) {
        return cross(material.low(), material.mid(), material.high());
    }

    public float low() {
        return low;
    }

    public float mid() {
        return mid;
    }

    public float high() {
        return high;
    }
}
