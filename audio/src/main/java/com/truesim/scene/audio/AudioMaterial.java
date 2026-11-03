package com.truesim.scene.audio;

/**
 * What a surface lets through when a sound crosses it (§2.11), per band, as linear gains: a curtain lets most through,
 * a concrete wall little and the highs least. Rough values from typical sound reduction indices.
 *
 * @param low  the gain below about 250 Hz
 * @param mid  the gain in the middle
 * @param high the gain above about 4 kHz
 */
public record AudioMaterial(float low, float mid, float high) {

    /** Lets everything through (an unknown material, or none). */
    public static final AudioMaterial OPEN = new AudioMaterial(1, 1, 1);
    /** Cloth, foliage. */
    public static final AudioMaterial CURTAIN = new AudioMaterial(0.9f, 0.7f, 0.5f);
    /** Glass in a frame. */
    public static final AudioMaterial GLASS = new AudioMaterial(0.5f, 0.3f, 0.2f);
    /** A wooden door or wall. */
    public static final AudioMaterial WOOD = new AudioMaterial(0.5f, 0.25f, 0.1f);
    /** Brick. */
    public static final AudioMaterial BRICK = new AudioMaterial(0.25f, 0.08f, 0.03f);
    /** Concrete or rock. */
    public static final AudioMaterial CONCRETE = new AudioMaterial(0.15f, 0.04f, 0.01f);

    public AudioMaterial {
        if (!(low >= 0 && low <= 1 && mid >= 0 && mid <= 1 && high >= 0 && high <= 1)) {
            throw new IllegalArgumentException("Transmission gains are from 0 to 1: " + low + ", " + mid + ", " + high);
        }
    }
}
