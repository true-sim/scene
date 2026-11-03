package com.truesim.scene.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sounds of a world: slots and handles, the change log, time, concurrency and the UI store. */
class SoundsTest {

    static final SoundParameter INTENSITY = SoundParameter.of("intensity", 0.5f, 0, 1);

    private SceneGraph scene;
    private Sounds sounds;

    static AssetRef<AudioClip> clip(int n) {
        return AssetRef.of(AudioAssets.CLIP, new AssetId(3, n));
    }

    @BeforeEach
    void setUp() {
        scene = new SceneGraph(new ChangeClock());
        sounds = new Sounds(scene);
    }

    private long node(double x, double y, double z) {
        long node = scene.create();
        scene.setTranslation(node, x, y, z);
        scene.update();
        return node;
    }

    private List<String> changes(long since) {
        List<String> out = new ArrayList<>();
        assertThat(sounds.changesSince(since, (id, changes) -> out.add((int) id + ":" + changes))).isTrue();
        return out;
    }

    @Test
    void playingLogsAndPlacesInstances() {
        long since = scene.clock().now();
        long n = node(1, 2, 3);
        SoundInstance a = sounds.play(Sound.of(clip(1)), n);
        SoundInstance b = sounds.play(Sound.of(clip(2)), 4, 5, 6);
        long c = sounds.fire(Sound.builder().root(Play.clip(clip(3))).spatial(Spatial.TWO_D).build());
        SoundInstance d = sounds.play(clip(4), n);
        SoundInstance e = sounds.play(clip(4));
        assertThat(sounds.size()).isEqualTo(5);
        assertThat(changes(since)).containsExactly("0:1", "1:1", "2:1", "3:1", "4:1");
        assertThat(sounds.node(a.id())).isEqualTo(n);
        assertThat(sounds.position(a.id(), 2)).isEqualTo(3);
        assertThat(sounds.rotation(a.id(), 3)).isEqualTo(1);
        assertThat(sounds.position(b.id(), 0)).isEqualTo(4);
        assertThat(sounds.node(b.id())).isEqualTo(NodeHandle.NONE);
        assertThat(sounds.placed(a.id())).isTrue();
        assertThat(sounds.placed(c)).isFalse();
        assertThat(sounds.sound(d.id())).isEqualTo(Sound.of(clip(4))).isSameAs(sounds.sound(e.id()));
        assertThat(sounds.seed(a.id())).isNotEqualTo(sounds.seed(b.id()));
        assertThat(sounds.start(a.id())).isZero();
        assertThat(sounds.scene()).isSameAs(scene);
        assertThat(sounds.clock()).isSameAs(scene.clock());
        assertThat(sounds.isUi()).isFalse();
        List<Long> all = new ArrayList<>();
        sounds.forEach(all::add);
        assertThat(all).containsExactly(a.id(), b.id(), c, d.id(), e.id());
        b.kill();
        all.clear();
        sounds.forEach(all::add);
        assertThat(all).containsExactly(a.id(), c, d.id(), e.id());
        for (int i = 0; i < 40; i++) {
            sounds.fire(Sound.of(clip(9)), n); // more than the pending list holds, in one frame
        }
        sounds.advance(0.0);
        assertThat(sounds.voices(c)).isEqualTo(1);
        assertThatThrownBy(() -> sounds.fire(Sound.of(clip(1)), NodeHandle.NONE))
            .hasMessageContaining("Not a node of the scene");
    }

    @Test
    void handlesGoInertAndSlotsAreReusedWithANewGeneration() {
        SoundInstance a = sounds.play(Sound.builder().root(Play.clip(clip(1)).volume(INTENSITY,
            Curve.points(0, 0, 1, 1))).build(), 0, 0, 0);
        long since = scene.clock().now();
        a.kill();
        assertThat(a.alive()).isFalse();
        assertThat(a.time()).isNaN();
        assertThat(a.get(INTENSITY)).isNaN();
        a.set(INTENSITY, 1).set(INTENSITY, 1, 2).volume(-3, 1).pitch(2).pause(true).timeScale(2).moveTo(1, 1, 1)
            .seed(3).set(SoundTest.SURFACE, SoundTest.Surface.WOOD);
        a.stop();
        a.stop(1);
        a.kill();
        sounds.ended(a.id());
        sounds.drop(a.id());
        assertThat(changes(since)).containsExactly("0:4");
        SoundInstance b = sounds.play(Sound.of(clip(2)), 0, 0, 0);
        assertThat(b.time()).isZero();
        sounds.input(a.id(), 0);
        assertThat((int) b.id()).isEqualTo((int) a.id());
        assertThat(b.id()).isNotEqualTo(a.id());
        assertThat(a.alive()).isFalse();
        assertThat(b.alive()).isTrue();
        assertThatThrownBy(() -> sounds.sound(a.id())).hasMessageStartingWith("No sound plays as ");
        assertThat(sounds.alive(-1)).isFalse();
        assertThat(sounds.alive(1L << 40 | 99)).isFalse();
    }

    @Test
    void settersChangeAndLog() {
        Sound rain = Sound.builder().root(Switch.on(SoundTest.SURFACE).otherwise(Play.clip(clip(1))
            .volume(INTENSITY, Curve.points(0, 0, 1, 1)))).build();
        SoundInstance r = sounds.play(rain, 0, 0, 0);
        long since = scene.clock().now();
        assertThat(r.get(INTENSITY)).isEqualTo(0.5f);
        r.set(INTENSITY, 2).set(SoundTest.SURFACE, SoundTest.Surface.GRAVEL);
        assertThat(r.get(INTENSITY)).isEqualTo(1);
        assertThat(sounds.parameter(r.id(), 0)).isEqualTo(1);
        assertThat(sounds.parameterRamp(r.id())).isEqualTo((float) Sounds.DEFAULT_RAMP);
        r.set(INTENSITY, 0.25f, 4);
        assertThat(sounds.parameterRamp(r.id())).isEqualTo(4);
        assertThat(sounds.switchValue(r.id(), 0)).isEqualTo(SoundTest.Surface.GRAVEL.ordinal());
        r.volume(-6, 1).pitch(1.5f).pause(true).timeScale(0.5f).seed(42).moveTo(7, 8, 9);
        assertThat(sounds.volume(r.id())).isEqualTo(-6);
        assertThat(sounds.volumeRamp(r.id())).isEqualTo(1);
        assertThat(sounds.pitch(r.id())).isEqualTo(1.5f);
        assertThat(sounds.paused(r.id())).isTrue();
        assertThat(sounds.timeScaleOf(r.id())).isEqualTo(0.5f);
        assertThat(sounds.seed(r.id())).isEqualTo(42);
        assertThat(sounds.position(r.id(), 1)).isEqualTo(8);
        int id = (int) r.id();
        assertThat(changes(since)).containsExactly(id + ":8", id + ":128", id + ":8", id + ":8", id + ":8",
            id + ":16", id + ":16", id + ":64", id + ":32");
        assertThatThrownBy(() -> r.set(SoundParameter.of("other", 0, 0, 1), 1))
            .hasMessageStartingWith("The sound reads no SoundParameter");
        assertThatThrownBy(() -> r.set(SwitchGroup.of("other", Thread.State.class, Thread.State.NEW),
            Thread.State.NEW)).hasMessageStartingWith("The sound reads no SwitchGroup");
        assertThat(r.get(SoundParameter.of("other", 0, 0, 1))).isNaN();
        assertThatThrownBy(() -> r.pitch(0)).hasMessage("Not a pitch ratio: 0.0");
        assertThatThrownBy(() -> r.pitch(Float.POSITIVE_INFINITY)).hasMessageContaining("pitch ratio");
        assertThatThrownBy(() -> r.timeScale(-1)).hasMessage("Not a time scale: -1.0");
        assertThatThrownBy(() -> r.timeScale(40_000)).hasMessage("Not a time scale: 40000.0");
        assertThatThrownBy(() -> r.stop(-1)).hasMessage("Not a fade: -1.0");
        assertThatThrownBy(() -> r.stop(Double.POSITIVE_INFINITY)).hasMessageContaining("Not a fade");
    }

    @Test
    void timeMovesByScaleAndHoldsWhilePaused() {
        Sound world = Sound.of(clip(1));
        Sound flat = Sound.builder().root(Play.clip(clip(1))).spatial(Spatial.TWO_D).build();
        Sound always = Sound.builder().root(Play.clip(clip(1))).ignoresPause().build();
        long a = sounds.fire(world, 0, 0, 0);
        long b = sounds.fire(flat);
        long c = sounds.fire(always, 0, 0, 0);
        sounds.timeScale(0.5f);
        sounds.advance(1.0);
        assertThat(sounds.now()).isEqualTo(1_000_000);
        assertThat(sounds.time(a)).isEqualTo(500_000);
        assertThat(sounds.time(b)).as("2D sounds do not follow the time scale").isEqualTo(1_000_000);
        assertThat(sounds.rate(a)).isEqualTo(0.5f);
        assertThat(sounds.timeScale()).isEqualTo(0.5f);
        long since = scene.clock().now();
        sounds.pause(true);
        sounds.pause(true);
        assertThat(changes(since)).hasSize(3);
        assertThat(sounds.paused()).isTrue();
        sounds.advance(1_000_000L);
        assertThat(sounds.time(a)).isEqualTo(500_000);
        assertThat(sounds.holding(a)).isTrue();
        assertThat(sounds.rate(a)).isZero();
        assertThat(sounds.time(c)).as("ignores pause").isEqualTo(1_000_000);
        assertThat(sounds.holding(c)).isFalse();
        sounds.pause(false);
        assertThat(sounds.holding(a)).isFalse();
        sounds.kill(b);
        sounds.pause(true); // a dead slot is not logged
        sounds.pause(false);
        sounds.pause(a, true);
        assertThat(sounds.holding(a)).isTrue();
        sounds.advance(0.5);
        assertThat(sounds.time(a)).isEqualTo(500_000);
        sounds.pause(a, false);
        sounds.timeScale(1);
        sounds.timeScale(1);
        sounds.timeScale(a, 2);
        sounds.advance(0.25);
        assertThat(sounds.time(a)).isEqualTo(1_000_000);
        assertThatThrownBy(() -> sounds.advance(-1L)).hasMessage("Not a time step: -1");
        assertThatThrownBy(() -> sounds.advance(-1.0)).hasMessage("Not a time step: -1.0");
        assertThatThrownBy(() -> sounds.advance(Double.POSITIVE_INFINITY)).hasMessageContaining("time step");
        assertThatThrownBy(() -> sounds.advance(Double.NaN)).hasMessageContaining("time step");
    }

    @Test
    void stoppingEndsAfterTheFadeOrWaitsForTheOutro() {
        Sound plain = Sound.builder().root(Play.clip(clip(1))).releaseFade(0.1).build();
        Sound outro = Sound.builder().root(Segments.loop(clip(1)).outro(clip(2))).build();
        Sound limited = Sound.builder().root(Play.clip(clip(1))).maxDuration(0.5).build();
        long a = sounds.fire(plain, 0, 0, 0);
        long b = sounds.fire(outro, 0, 0, 0);
        long c = sounds.fire(outro, 0, 0, 0);
        long d = sounds.fire(limited, 0, 0, 0);
        sounds.advance(0.0);
        sounds.stop(a);
        sounds.stop(b);
        sounds.stop(c, 0.2);
        assertThat(sounds.stopped(a)).isTrue();
        assertThat(sounds.stopFade(a)).isEqualTo(0.1f);
        assertThat(sounds.stopped(d)).isFalse();
        assertThat(sounds.stopFade(d)).isZero();
        sounds.advance(0.1);
        assertThat(sounds.alive(a)).isFalse();
        assertThat(sounds.alive(b)).as("waits for the engine to end its outro").isTrue();
        assertThat(sounds.alive(c)).isTrue();
        sounds.advance(0.1);
        assertThat(sounds.alive(c)).as("an explicit fade skips the outro").isFalse();
        sounds.stop(b, 0);
        sounds.advance(0.0);
        assertThat(sounds.alive(b)).isFalse();
        sounds.advance(0.3);
        assertThat(sounds.alive(d)).as("max duration").isFalse();
    }

    @Test
    void aRemovedNodeLeavesItsSoundsWhereTheyWereStopped() {
        long n = node(5, 0, 0);
        long a = sounds.fire(Sound.builder().root(Play.clip(clip(1))).releaseFade(1).build(), n);
        long b = sounds.fire(Sound.of(clip(1)), n);
        sounds.stop(b, 5);
        scene.setTranslation(n, 6, 0, 0);
        scene.update();
        sounds.advance(0.0);
        assertThat(sounds.position(a, 0)).isEqualTo(6);
        long since = scene.clock().now();
        scene.remove(n);
        scene.update();
        sounds.advance(0.0);
        assertThat(sounds.node(a)).isEqualTo(NodeHandle.NONE);
        assertThat(sounds.position(a, 0)).isEqualTo(6);
        assertThat(sounds.stopped(a)).isTrue();
        assertThat(sounds.stopFade(b)).as("kept its own fade").isEqualTo(5);
        assertThat(changes(since)).containsExactly((int) a + ":32", (int) a + ":2", (int) b + ":32");
    }

    @Test
    void theLogOverflowsToARebuild() {
        Sounds small = new Sounds(scene, 4);
        long since = scene.clock().now();
        for (int i = 0; i < 6; i++) {
            small.fire(Sound.of(clip(i)), 0, 0, 0);
        }
        assertThat(small.changesSince(since, (id, c) -> { })).isFalse();
        List<Long> rebuilt = new ArrayList<>();
        small.forEach(rebuilt::add);
        assertThat(rebuilt).hasSize(6);
        long later = scene.clock().now();
        small.fire(Sound.of(clip(9)), 0, 0, 0);
        List<Integer> seen = new ArrayList<>();
        assertThat(small.changesSince(later, (id, c) -> seen.add(c))).isTrue();
        assertThat(seen).containsExactly(Sounds.ADDED);
        assertThatThrownBy(() -> new Sounds(scene, 0)).hasMessage("The change log needs room: 0");
    }

    @Test
    void concurrencyStealsOrRejectsAtStart() {
        long listener = node(0, 0, 0);
        sounds.listeners().render(node(50, 0, 0), ListenerMix.mono());
        sounds.listeners().device(listener);
        Sound oldest = Sound.builder().root(Play.clip(clip(1))).concurrency(Concurrency.max(2)).build();
        long a = sounds.fire(oldest, 1, 0, 0);
        sounds.advance(0.01);
        long b = sounds.fire(oldest, 1, 0, 0);
        long c = sounds.fire(oldest, 1, 0, 0);
        assertThat(sounds.alive(a)).isFalse();
        assertThat(sounds.alive(b)).isTrue();
        assertThat(sounds.alive(c)).isTrue();

        Sound farthest = Sound.builder().root(Play.clip(clip(2)))
            .concurrency(Concurrency.max(2).steal(Concurrency.Steal.FARTHEST)).build();
        long near = sounds.fire(farthest, 1, 0, 0);
        long far = sounds.fire(farthest, 30, 0, 0);
        long next = sounds.fire(farthest, 2, 0, 0);
        assertThat(sounds.alive(far)).isFalse();
        assertThat(sounds.alive(near)).isTrue();
        assertThat(sounds.alive(next)).isTrue();

        Sound quiet = Sound.builder().root(Play.clip(clip(3))).attenuation(Attenuation.near(1, 50))
            .concurrency(Concurrency.max(1).steal(Concurrency.Steal.QUIETEST)).build();
        long q = sounds.fire(quiet, 40, 0, 0);
        long r = sounds.fire(quiet, 1, 0, 0);
        assertThat(sounds.alive(q)).isFalse();
        assertThat(sounds.alive(r)).isTrue();

        Sound low = Sound.builder().root(Play.clip(clip(4))).priority(Priority.LOW)
            .concurrency(Concurrency.max(2).steal(Concurrency.Steal.LOWEST_PRIORITY).group("mix")).build();
        Sound high = Sound.builder().root(Play.clip(clip(5))).priority(Priority.HIGH)
            .concurrency(Concurrency.max(2).steal(Concurrency.Steal.LOWEST_PRIORITY).group("mix")).build();
        long h = sounds.fire(high, 0, 0, 0);
        long l = sounds.fire(low, 0, 0, 0);
        long h2 = sounds.fire(high, 0, 0, 0);
        assertThat(sounds.alive(l)).isFalse();
        assertThat(sounds.alive(h)).isTrue();
        assertThat(sounds.alive(h2)).isTrue();

        Sound reject = Sound.builder().root(Play.clip(clip(6)))
            .concurrency(Concurrency.max(1).steal(Concurrency.Steal.REJECT_NEW)).build();
        long first = sounds.fire(reject, 0, 0, 0);
        SoundInstance second = sounds.play(reject, 0, 0, 0);
        assertThat(sounds.alive(first)).isTrue();
        assertThat(second.id()).isZero();
        assertThat(second.alive()).isFalse();
        assertThat(sounds.rejected()).isEqualTo(1);
        assertThat(sounds.fire(reject, listener)).isZero();
        sounds.stop(first);
        long third = sounds.fire(reject, 0, 0, 0);
        assertThat(sounds.alive(third)).as("a stopping instance leaves room").isTrue();
        assertThat(sounds.rejected()).isEqualTo(2);

        Sound retrigger = Sound.builder().root(Play.clip(clip(7))).concurrency(Concurrency.max(10).retrigger(0.05))
            .build();
        assertThat(sounds.fire(retrigger)).isNotZero();
        assertThat(sounds.fire(retrigger)).isZero();
        sounds.advance(0.05);
        assertThat(sounds.fire(retrigger)).isNotZero();
        assertThat(sounds.rejected()).isEqualTo(3);
        Sound unlimited = Sound.builder().root(Play.clip(clip(8))).concurrency(Concurrency.UNLIMITED.retrigger(1))
            .build();
        assertThat(sounds.fire(unlimited)).isNotZero();
        assertThat(sounds.fire(unlimited)).isZero();
    }

    @Test
    void farthestWithoutListenersStealsTheFirst() {
        Sound farthest = Sound.builder().root(Play.clip(clip(2)))
            .concurrency(Concurrency.max(1).steal(Concurrency.Steal.FARTHEST)).build();
        long a = sounds.fire(farthest, 1, 0, 0);
        long b = sounds.fire(farthest, 30, 0, 0);
        assertThat(sounds.alive(a)).isFalse();
        assertThat(sounds.alive(b)).isTrue();
    }

    @Test
    void droppedInstancesAreCounted() {
        long a = sounds.fire(Sound.of(clip(1)), 0, 0, 0);
        sounds.drop(a);
        sounds.drop(a);
        assertThat(sounds.dropped()).isEqualTo(1);
        assertThat(sounds.alive(a)).isFalse();
    }

    @Test
    void theUiStoreHasNoPlacesAndIsNeverPaused() {
        Sounds ui = Sounds.ui();
        assertThat(ui.isUi()).isTrue();
        assertThat(ui.scene()).isNull();
        SoundInstance click = ui.play(clip(1));
        assertThat(ui.sound(click.id()).bus()).isEqualTo(Bus.UI);
        assertThat(ui.sound(click.id()).spatial()).isEqualTo(Spatial.TWO_D);
        assertThat(ui.placed(click.id())).isFalse();
        assertThatThrownBy(() -> ui.play(Sound.of(clip(2)))).hasMessageContaining("2D sounds only");
        assertThatThrownBy(() -> ui.fire(Sound.of(clip(2)), 1, 2, 3)).hasMessageContaining("no nodes or positions");
        assertThatThrownBy(() -> ui.fire(Sound.of(clip(2)), 1)).hasMessageContaining("no nodes or positions");
        assertThatThrownBy(() -> ui.pause(true)).hasMessage("The UI store is never paused");
        assertThatThrownBy(() -> ui.timeScale(0.5f)).hasMessage("The UI store is never time-scaled");
        assertThatThrownBy(() -> ui.listeners().device(1)).hasMessageContaining("no listeners");
        assertThatThrownBy(() -> ui.reverbZones().sphere(1, 1, 0, AudioEffect.named("reverb"), 0))
            .hasMessageContaining("no reverb zones");
        ui.advance(0.5);
        assertThat(ui.time(click.id())).isEqualTo(500_000);
    }

    @Test
    void onlyTheOwnerChangesTheStore() throws InterruptedException {
        scene.clock().ownedBy(Thread.currentThread());
        Throwable[] thrown = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                sounds.fire(Sound.of(clip(1)));
            } catch (IllegalStateException e) {
                thrown[0] = e;
            }
        }, "other");
        other.start();
        other.join();
        assertThat(sounds.fire(Sound.of(clip(1)))).as("the owner may").isNotZero();
        assertThat(thrown[0]).hasMessage("The sounds change on " + Thread.currentThread().getName()
            + " only, not on other");
    }

    @Test
    void advancingAndFiringAllocateNothing() {
        long n = node(1, 0, 0);
        sounds.listeners().device(node(0, 0, 0));
        Sound shot = Sound.builder().root(Random.of(clip(1), clip(2), clip(3)).noRepeat(1)).build();
        long since = scene.clock().now();
        Sounds.Visitor visitor = (id, changes) -> { };
        long[] last = {0};
        assertAllocationFree(() -> {
            long id = sounds.fire(shot, n);
            sounds.advance(1_000L);
            sounds.voiceGain(id, 0);
            sounds.changesSince(since, visitor);
            if (last[0] != 0) {
                sounds.kill(last[0]);
            }
            last[0] = id;
        });
    }
}
