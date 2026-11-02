package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * What a rendering listener mixes (AU12): a reduced mix of its own, panned only (its output is spatialized again where
 * it plays), from some of the world's buses, with a voice budget of its own and an optional reach; and where else its
 * mix goes besides sounds playing it as input: a device bus (picture in picture) or a tap the game reads.
 *
 * <pre>{@code
 * ListenerMix.mono().buses(Bus.SFX, Bus.AMBIENCE).voices(16).maxDistance(40)
 * ListenerMix.stereo().toBus(PIP)
 * }</pre>
 *
 * @param channels    1 (mono, the default) or 2
 * @param buses       the buses it hears; empty for all the world's except music and UI
 * @param voices      its voice budget
 * @param gain        its output gain, decibels
 * @param maxDistance metres beyond which it hears nothing; infinite by default
 * @param deviceBus   the device bus its mix also plays into; {@code null} for none
 * @param tapped      whether a tap receives its mix
 */
@AllowAllocation("set up once")
public record ListenerMix(int channels, List<Bus> buses, int voices, float gain, float maxDistance, Bus deviceBus,
        boolean tapped) {

    public ListenerMix {
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("A listener's mix is mono or stereo, not " + channels + " channels");
        }
        buses = List.copyOf(buses);
        if (voices < 1) {
            throw new IllegalArgumentException("A listener's mix needs a voice: " + voices);
        }
        if (!(maxDistance > 0)) {
            throw new IllegalArgumentException("Not a distance: " + maxDistance);
        }
    }

    /** A mono mix of the world's buses but music and UI, 16 voices, unlimited reach. */
    public static ListenerMix mono() {
        return new ListenerMix(1, List.of(), 16, 0, Float.POSITIVE_INFINITY, null, false);
    }

    /** As {@link #mono()}, in stereo. */
    public static ListenerMix stereo() {
        return new ListenerMix(2, List.of(), 16, 0, Float.POSITIVE_INFINITY, null, false);
    }

    /** A copy hearing only {@code heard}. */
    public ListenerMix buses(Bus... heard) {
        return new ListenerMix(channels, List.of(heard), voices, gain, maxDistance, deviceBus, tapped);
    }

    /** A copy with a budget of {@code count} voices. */
    public ListenerMix voices(int count) {
        return new ListenerMix(channels, buses, count, gain, maxDistance, deviceBus, tapped);
    }

    /** A copy with its output at {@code db} decibels. */
    public ListenerMix gain(double db) {
        return new ListenerMix(channels, buses, voices, (float) db, maxDistance, deviceBus, tapped);
    }

    /** A copy hearing nothing beyond {@code metres}. */
    public ListenerMix maxDistance(double metres) {
        return new ListenerMix(channels, buses, voices, gain, (float) metres, deviceBus, tapped);
    }

    /** A copy also playing into device bus {@code bus}. */
    public ListenerMix toBus(Bus bus) {
        return new ListenerMix(channels, buses, voices, gain, maxDistance, Objects.requireNonNull(bus, "bus"), tapped);
    }

    /** A copy whose mix a tap receives. */
    public ListenerMix tap() {
        return new ListenerMix(channels, buses, voices, gain, maxDistance, deviceBus, true);
    }

    /** Whether it hears {@code bus}: one of its buses or their children, or with none given any but music and UI. */
    public boolean hears(Bus bus) {
        for (Bus b = bus; b != null; b = b.parent()) {
            if (buses.isEmpty() ? b.equals(Bus.MUSIC) || b.equals(Bus.UI) : buses.contains(b)) {
                return !buses.isEmpty();
            }
        }
        return buses.isEmpty();
    }
}
