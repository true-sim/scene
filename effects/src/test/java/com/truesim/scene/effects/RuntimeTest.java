package com.truesim.scene.effects;

import static com.truesim.scene.effects.ValuesTest.INTENSITY;
import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The emitter runtime's spawns: counts against closed forms, frame splits, prewarm, distance, stops and threads. */
class RuntimeTest {

    private static final BigInteger UNIT = BigInteger.valueOf(Effects.TICKS_PER_SECOND).shiftLeft(32);

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);

    /** A steady rate after a delay, and three loops of a rate with two bursts each. */
    static EffectData timed() {
        return EffectData.builder()
            .emitter("steady", e -> e.delay(0.25).spawn(Spawn.rate(37.5f)).init(Init.lifetime(2)))
            .emitter("looped", e -> e.delay(0.1).duration(1).loopDelay(0.5).loops(3).capacity(100)
                .spawn(Spawn.rate(10), Spawn.burst(0, 5), Spawn.burst(0.75, 5)).init(Init.lifetime(2)))
            .build();
    }

    /** Particles a rate of {@code perSecond} has spawned by its emitter's own time {@code t}, in closed form. */
    static long rateClosed(EmitterData e, double perSecond, long t) {
        BigInteger rate = BigInteger.valueOf((long) (perSecond * 4294967296.0));
        return rate.multiply(BigInteger.valueOf(EffectRuntime.active(e, t))).divide(UNIT).longValueExact();
    }

    /** Bursts of {@code count} at {@code time} into each loop fired by the emitter's own time {@code t}. */
    static long burstsClosed(EmitterData e, double time, int count, long t) {
        long fired = 0;
        long period = EffectRuntime.ticks(e.duration() + e.loopDelay());
        for (int k = 0; e.loops() == 0 || k < e.loops(); k++) {
            if (k * period + EffectRuntime.ticks(time) > t) {
                break;
            }
            fired += count;
        }
        return fired;
    }

    /** The particles each emitter of {@code effect} spawned in the last update. */
    private long[] spawnedNow(long effect, int emitters) {
        long[] counts = new long[emitters];
        SpawnRecords r = runtime.spawns();
        for (int i = 0; i < r.size(); i++) {
            if (r.effect(i) == effect) {
                counts[r.emitter(i)] += r.count(i);
            }
        }
        return counts;
    }

    /** Checks each frame's counts against the closed forms; returns the looped emitter's total. */
    private long countsMatchClosedForms(long[] frames) {
        EffectData data = timed();
        EmitterData steady = data.emitters().get(0);
        EmitterData looped = data.emitters().get(1);
        long effect = effects.play(data, 0, 0, 0).id();
        long t = 0;
        long[] before = new long[2];
        for (long ticks : frames) {
            t += ticks;
            runtime.update(ticks);
            long[] closed = {
                rateClosed(steady, 37.5, t - 250_000),
                rateClosed(looped, 10, t - 100_000) + burstsClosed(looped, 0, 5, t - 100_000)
                    + burstsClosed(looped, 0.75, 5, t - 100_000)};
            long[] now = spawnedNow(effect, 2);
            assertThat(now[0]).as("steady at " + t).isEqualTo(closed[0] - before[0]);
            assertThat(now[1]).as("looped at " + t).isEqualTo(closed[1] - before[1]);
            assertThat(runtime.spawned(effect, 0)).isEqualTo(closed[0]);
            before = closed;
        }
        return before[1];
    }

    @Test
    void countsPerFrameMatchClosedFormsAt30_60_90And144Hz() {
        for (int hz : new int[] {30, 60, 90, 144}) {
            long tick = Math.round(1e6 / hz);
            long[] frames = new long[6 * hz];
            java.util.Arrays.fill(frames, tick);
            assertThat(countsMatchClosedForms(frames)).as("three loops: 10 a second and two bursts of 5")
                .isEqualTo(3 * (10 + 10));
        }
    }

    @Test
    void countsMatchClosedFormsThroughHitches() {
        List<Long> frames = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            frames.add(16_667L);
        }
        frames.add(300_000L);   // across the first loop's burst at 0.75
        frames.add(16_667L);
        frames.add(1_250_000L); // across a loop's end, its delay and the next loop's start
        frames.add(0L);
        frames.add(11_111L);
        frames.add(2_000_000L);
        frames.add(16_667L);
        countsMatchClosedForms(frames.stream().mapToLong(Long::longValue).toArray());
    }

    /** Spawn index to birth time, in microseconds of the effect's time, over a run of {@code frames}. */
    private static Map<Long, Long> births(long[] frames) {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Effects effects = new Effects(scene);
        EffectRuntime runtime = new EffectRuntime(effects);
        long effect = effects.play(timed(), 0, 0, 0).seed(7).id();
        Map<Long, Long> births = new TreeMap<>();
        for (long ticks : frames) {
            runtime.update(ticks);
            SpawnRecords r = runtime.spawns();
            for (int i = 0; i < r.size(); i++) {
                for (int p = 0; p < r.count(i); p++) {
                    double age = r.age(i) - p * (double) r.interval(i);
                    long born = effects.time(effect) - Math.round(age * 1e6);
                    births.put(r.emitter(i) * 1_000_000L + r.firstIndex(i) + p, born);
                }
            }
        }
        return births;
    }

    @Test
    void theSameTicksSpawnTheSameParticlesHoweverFramesSplitThem() {
        long[] even = new long[300];
        java.util.Arrays.fill(even, 16_667);
        long[] uneven = new long[600];
        java.util.Random random = new java.util.Random(3);
        long left = 300 * 16_667L;
        for (int i = 0; i < uneven.length - 1; i++) {
            uneven[i] = Math.min(left, random.nextInt(30_000));
            left -= uneven[i];
        }
        uneven[uneven.length - 1] = left;
        Map<Long, Long> a = births(even);
        Map<Long, Long> b = births(uneven);
        assertThat(b.keySet()).isEqualTo(a.keySet());
        for (Map.Entry<Long, Long> birth : a.entrySet()) {
            assertThat(b.get(birth.getKey())).as("particle " + birth.getKey()).isCloseTo(birth.getValue(), within(2L));
        }
        assertThat(a).hasSize(178 + 60); // 37.5 a second for 4.7501 s; three loops of 10 and two bursts of 5
        // Births are where the rate says: particle i of the steady emitter at 0.25 s + (i + 1) / 37.5 s.
        assertThat(a.get(0L)).isCloseTo(250_000 + 26_667, within(2L));
        assertThat(a.get(9L)).isCloseTo(250_000 + 266_667, within(2L));
    }

    @Test
    void prewarmedEmittersSpawnTheirPastBackDated() {
        EffectData warm = EffectData.builder()
            .emitter("fire", e -> e.prewarm(2).spawn(Spawn.rate(10)).init(Init.lifetime(1)))
            .emitter("flash", e -> e.prewarm(0.5).spawn(Spawn.burst(0, 3)).init(Init.lifetime(1)))
            .emitter("old", e -> e.prewarm(3).spawn(Spawn.burst(0, 3)).init(Init.lifetime(1)))
            .emitter("late", e -> e.delay(1).prewarm(0.5).spawn(Spawn.burst(0, 3)))
            .build();
        long effect = effects.play(warm, 0, 0, 0).id();
        runtime.update(16_667L);
        SpawnRecords r = runtime.spawns();
        assertThat(r.size()).isEqualTo(2);
        // The fire's particles born in its first second died: their indices are used up.
        assertThat(r.emitter(0)).isZero();
        assertThat(r.flags(0)).isEqualTo(SpawnRecords.PREWARM);
        assertThat(r.count(0)).isEqualTo(10);
        assertThat(r.firstIndex(0)).isEqualTo(10);
        assertThat(r.age(0)).isCloseTo(0.916667f, within(1e-5f));
        assertThat(r.interval(0)).isCloseTo(0.1f, within(1e-6f));
        assertThat(r.burst(0)).isEqualTo(-1);
        assertThat(r.effect(0)).isEqualTo(effect);
        // The flash at 0 of its timeline, half a second before the effect.
        assertThat(r.emitter(1)).isEqualTo(1);
        assertThat(r.age(1)).isCloseTo(0.516667f, within(1e-5f));
        assertThat(runtime.spawned(effect, 2)).as("a burst too old to be alive").isEqualTo(3);
        assertThat(runtime.spawned(effect, 3)).as("not started").isZero();
        runtime.update(500_000L);
        assertThat(runtime.spawned(effect, 3)).isEqualTo(3);
        assertThat(runtime.spawns().flags(runtime.spawns().size() - 1)).isZero();
    }

    @Test
    void movingEmittersSpawnPerMetre() {
        long node = scene.create();
        scene.update();
        EffectData dust = EffectData.builder()
            .emitter("dust", e -> e.duration(1).loopDelay(1).loops(0).capacity(100)
                .spawn(Spawn.perDistance(Bind.constant(4))))
            .build();
        long effect = effects.play(dust, node).id();
        runtime.update(100_000L);
        assertThat(runtime.spawns().size()).as("no way travelled yet").isZero();
        for (int frame = 1; frame <= 5; frame++) {
            scene.setTranslation(node, frame * 0.5, 0, 0);
            scene.update();
            runtime.update(100_000L);
            assertThat(spawnedNow(effect, 1)[0]).as("frame " + frame).isEqualTo(2);
        }
        SpawnRecords r = runtime.spawns();
        assertThat(r.interval(0)).isCloseTo(0.05f, within(1e-6f));
        assertThat(r.age(0)).isCloseTo(0.05f, within(1e-6f));
        // A fifth of the frame in the loop's delay: four fifths of the way count.
        scene.setTranslation(node, 3.75, 0, 0);
        scene.update();
        runtime.update(500_000L);
        assertThat(spawnedNow(effect, 1)[0]).isEqualTo(4);
        // In the delay: none.
        scene.setTranslation(node, 4.75, 0, 0);
        scene.update();
        runtime.update(200_000L);
        assertThat(spawnedNow(effect, 1)[0]).isZero();
        // Standing still: none.
        runtime.update(500_000L);
        assertThat(spawnedNow(effect, 1)[0]).isZero();
    }

    @Test
    void stoppedEffectsSpawnNoMore() {
        EffectData fire = EffectData.builder()
            .emitter("flames", e -> e.spawn(Spawn.rate(10)).init(Init.lifetime(1)))
            .emitter("pops", e -> e.duration(1).loops(0).spawn(Spawn.burst(0.5, 2)).init(Init.lifetime(1)))
            .build();
        Effect torch = effects.play(fire, 0, 0, 0);
        runtime.update(250_000L);
        assertThat(spawnedNow(torch.id(), 2)).containsExactly(2, 0);
        torch.stop();
        runtime.update(900_000L);
        assertThat(runtime.spawns().size()).isZero();
        assertThat(torch.alive()).isTrue();
        runtime.update(100_000L);
        assertThat(torch.alive()).as("its particles lived a second after the stop").isFalse();
    }

    @Test
    void aRecordSpawnsAtMostItsEmittersCapacityTheYoungest() {
        EffectData rain = EffectData.builder()
            .emitter("drops", e -> e.capacity(50).spawn(Spawn.rate(1000)).init(Init.lifetime(0.05f)))
            .build();
        long effect = effects.play(rain, 0, 0, 0).id();
        runtime.update(1_000_000L);
        SpawnRecords r = runtime.spawns();
        assertThat(r.count(0)).isEqualTo(50);
        assertThat(r.firstIndex(0)).isEqualTo(950);
        assertThat(r.age(0)).isCloseTo(0.049f, within(1e-5f));
        assertThat(runtime.spawned(effect, 0)).isEqualTo(1000);
    }

    @Test
    void parametersDriveRatesAndBurstCounts() {
        EffectData driven = EffectData.builder().parameter(INTENSITY, 1)
            .emitter("rate", e -> e.capacity(100).spawn(Spawn.rate(Bind.param(INTENSITY).times(10))))
            .emitter("curve", e -> e.duration(1).loops(0).capacity(50)
                .spawn(Spawn.burst(0, Bind.param(INTENSITY, FloatCurve.of(0, 0, 2, 20)))))
            .emitter("many", e -> e.duration(1).capacity(50).spawn(Spawn.burst(0, 1), Spawn.burst(0.1, 1),
                Spawn.burst(0.2, 1), Spawn.burst(0.3, 1), Spawn.burst(0.4, 1), Spawn.rate(10)))
            .build();
        Effect e = effects.play(driven, 0, 0, 0).set(INTENSITY, 2);
        runtime.update(1_000_000L);
        assertThat(spawnedNow(e.id(), 3)).containsExactly(20, 40, 15);
    }

    @Test
    void randomBurstCountsAreDrawnPerLoopFromTheSeed() {
        EffectData pops = EffectData.builder()
            .emitter("pops", e -> e.duration(1).loops(0).capacity(20).spawn(Spawn.burst(0, Bind.random(1, 9))))
            .build();
        List<Long> first = new ArrayList<>();
        List<Long> second = new ArrayList<>();
        for (List<Long> counts : List.of(first, second)) {
            Effects store = new Effects(scene);
            EffectRuntime r = new EffectRuntime(store);
            long effect = store.play(pops, 0, 0, 0).seed(11).id();
            for (int loop = 0; loop < 8; loop++) {
                r.update(1_000_000L);
                for (int i = 0; i < r.spawns().size(); i++) {
                    counts.add((long) r.spawns().count(i));
                }
            }
            assertThat(r.spawned(effect, 0)).isPositive();
        }
        assertThat(second).isEqualTo(first);
        assertThat(first).allMatch(c -> c >= 1 && c <= 9);
        assertThat(first.stream().distinct().count()).isGreaterThan(1);
        assertThat(EffectRuntime.emitterSeed(11, 2)).isEqualTo(Hash.combine(11, 2));
        assertThat(EffectRuntime.particleSeed(5, 9)).isEqualTo(Hash.combine(5, 9));
    }

    @Test
    void qualityScalesSpawnsAndDisablesEmitters() {
        EffectType scaled = EffectType.builder("scaled").spawnScale(Quality.LOW, 0.5f).build();
        EffectData sparks = EffectData.builder(scaled)
            .emitter("sparks", e -> e.spawn(Spawn.burst(0, 10)))
            .emitter("extra", e -> e.minimumQuality(Quality.HIGH).spawn(Spawn.burst(0, 10), Spawn.rate(5)))
            .emitter("trickle", e -> e.spawn(Spawn.rate(1e-12f)))
            .build();
        runtime.quality(Quality.LOW);
        assertThat(runtime.quality()).isEqualTo(Quality.LOW);
        long low = effects.play(sparks, 0, 0, 0).id();
        runtime.update(10_000L);
        assertThat(spawnedNow(low, 3)).containsExactly(5, 0, 0);
        assertThat(runtime.spawnScale(low)).isEqualTo(0.5f);
        runtime.quality(Quality.EPIC);
        long epic = effects.play(sparks, 0, 0, 0).id();
        runtime.update(10_000L);
        assertThat(spawnedNow(epic, 3)).containsExactly(10, 10, 0);
    }

    @Test
    void distantEffectsAreCulledAndDetailedWithHysteresis() {
        EffectType far = EffectType.builder("far").maxDistance(100).lod(50, 0.5f, 2, 0.25f).build();
        EffectData smoke = EffectData.builder(far).emitter("puffs", e -> e.spawn(Spawn.rate(10))).build();
        Effect e = effects.play(smoke, 105, 0, 0);
        runtime.addViewer(0, 0, 0);
        assertThat(runtime.viewerCount()).isOne();
        runtime.update(100_000L);
        assertThat(runtime.culled(e.id())).as("within the 10 % past its distance").isFalse();
        assertThat(runtime.lod(e.id())).isEqualTo(1);
        assertThat(runtime.drawnFraction(e.id())).isEqualTo(0.25f);
        assertThat(runtime.updateEvery(e.id())).isEqualTo(2);
        assertThat(runtime.spawnScale(e.id())).isEqualTo(0.5f);
        assertThat(runtime.distance(e.id())).isEqualTo(105);
        double[] path = {111, 105, 99, 52, 56, 52, 49};
        boolean[] culled = {true, true, false, false, false, false, false};
        int[] lods = {1, 1, 1, 1, 1, 1, 0};
        for (int i = 0; i < path.length; i++) {
            e.moveTo(path[i], 0, 0);
            runtime.update(100_000L);
            assertThat(runtime.culled(e.id())).as("at " + path[i]).isEqualTo(culled[i]);
            assertThat(runtime.lod(e.id())).as("at " + path[i]).isEqualTo(lods[i]);
        }
        e.moveTo(56, 0, 0);
        runtime.update(100_000L);
        e.moveTo(52, 0, 0);
        runtime.update(100_000L);
        assertThat(runtime.lod(e.id())).as("coarser past 55, finer below 50").isEqualTo(1);
        // Culled, it is paused by its type: no time passes, nothing spawns.
        e.moveTo(500, 0, 0);
        runtime.update(100_000L);
        double time = e.time();
        runtime.update(100_000L);
        assertThat(e.time()).isEqualTo(time);
        assertThat(runtime.spawns().size()).isZero();
        assertThat(runtime.drawnFraction(e.id())).isZero();
        // More viewers: the nearest counts.
        runtime.addViewer(500, 0, 10);
        runtime.addViewer(1000, 0, 0);
        runtime.addViewer(2000, 0, 0);
        runtime.addViewer(3000, 0, 0);
        runtime.update(100_000L);
        assertThat(runtime.distance(e.id())).isEqualTo(10);
        runtime.clearViewers();
        runtime.update(100_000L);
        assertThat(runtime.distance(e.id())).isZero();
    }

    @Test
    void capsKeepTheNearestWithHysteresis() {
        EffectType two = EffectType.builder("two").maxInstances(2).build();
        EffectData fire = EffectData.builder(two).emitter("flames", e -> e.spawn(Spawn.rate(10))).build();
        Effect a = effects.play(fire, 10, 0, 0);
        Effect b = effects.play(fire, 20, 0, 0);
        Effect c = effects.play(fire, 21, 0, 0);
        runtime.addViewer(0, 0, 0);
        runtime.update(100_000L);
        assertThat(List.of(runtime.culled(a.id()), runtime.culled(b.id()), runtime.culled(c.id())))
            .containsExactly(false, false, true);
        c.moveTo(19.5, 0, 0);
        runtime.update(100_000L);
        assertThat(runtime.culled(c.id())).as("not 10 % nearer than b").isTrue();
        c.moveTo(15, 0, 0);
        runtime.update(100_000L);
        assertThat(runtime.culled(c.id())).isFalse();
        assertThat(runtime.culled(b.id())).isTrue();
        // Equal distances: the lower slot wins.
        Effects store = new Effects(scene);
        EffectRuntime r = new EffectRuntime(store);
        EffectData single = EffectData.builder(EffectType.builder("one").maxInstances(1).build())
            .emitter("flames", e -> e.spawn(Spawn.rate(10))).build();
        long first = store.play(single, 20, 0, 0).id();
        long second = store.play(single, 0, 0, 20).id();
        r.addViewer(0, 0, 0);
        r.update(100_000L);
        assertThat(r.culled(first)).isFalse();
        assertThat(r.culled(second)).isTrue();
    }

    @Test
    void capsByAgeKillTheOldest() {
        EffectType hits = EffectType.builder("hits").maxInstances(2).significance(EffectType.Significance.AGE)
            .whenCulled(EffectType.Culled.KILL).build();
        EffectType free = EffectType.builder("free").build();
        EffectData hit = EffectData.builder(hits).emitter("chips", e -> e.spawn(Spawn.rate(10))).build();
        EffectData other = EffectData.builder(free).emitter("chips", e -> e.spawn(Spawn.rate(10))).build();
        Effect oldest = effects.play(hit, 0, 0, 0);
        effects.play(other, 0, 0, 0);
        runtime.update(100_000L);
        Effect middle = effects.play(hit, 0, 0, 0);
        runtime.update(100_000L);
        Effect youngest = effects.play(hit, 0, 0, 0);
        runtime.update(100_000L);
        assertThat(oldest.alive()).isFalse();
        assertThat(middle.alive()).isTrue();
        assertThat(youngest.alive()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.lod(oldest.id()));
    }

    @Test
    void culledEffectsOfSimulatingTypesSpawnOnUndrawn() {
        EffectType onward = EffectType.builder("onward").maxDistance(10).whenCulled(EffectType.Culled.SIMULATE)
            .build();
        EffectData fire = EffectData.builder(onward).emitter("flames", e -> e.spawn(Spawn.rate(10))).build();
        Effect e = effects.play(fire, 100, 0, 0);
        runtime.addViewer(0, 0, 0);
        runtime.update(1_000_000L);
        assertThat(runtime.culled(e.id())).isTrue();
        assertThat(runtime.drawnFraction(e.id())).isZero();
        assertThat(spawnedNow(e.id(), 1)[0]).isEqualTo(10);
    }

    @Test
    void burstsIntoSharedInstancesFireAfterTheirDelays() {
        EffectData impact = EffectData.builder()
            .emitter("chips", e -> e.delay(0.1).duration(1).spawn(Spawn.burst(0, 4), Spawn.burst(0.2, 2))
                .init(Init.lifetime(0.5f)))
            .emitter("flash", e -> e.duration(1).spawn(Spawn.burst(0, Bind.random(1, 3)), Spawn.rate(100)))
            .build();
        long shared = effects.burst(impact, 1, 0, 1, 0, 1, 0, 7);
        runtime.update(50_000L);
        SpawnRecords r = runtime.spawns();
        assertThat(r.size()).as("the flash now; rates do not run in shared instances").isOne();
        assertThat(r.emitter(0)).isEqualTo(1);
        assertThat(r.burst(0)).isZero();
        assertThat(effects.burstPayload(r.burst(0))).isEqualTo(7);
        assertThat(r.age(0)).isCloseTo(0.05f, within(1e-6f));
        runtime.update(100_000L);
        assertThat(spawnedNow(shared, 2)).containsExactly(4, 0);
        assertThat(runtime.spawns().age(0)).isCloseTo(0.05f, within(1e-6f));
        effects.burst(impact, 2, 0, 2, 0, 1, 0, 8);
        runtime.update(200_000L);
        assertThat(spawnedNow(shared, 2)[0]).as("the first call's second burst, the second call's first")
            .isEqualTo(2 + 4);
        assertThat(effects.burstCount()).isEqualTo(2);
        runtime.update(200_000L);
        assertThat(effects.burstCount()).as("the first call's bursts have all fired").isOne();
        // Calls into instances that ended, or stopped, spawn nothing.
        long other = effects.burst(impact, 100, 0, 0, 0, 1, 0, 0);
        effects.kill(other);
        long stopped = effects.burst(impact, 200, 0, 0, 0, 1, 0, 0);
        effects.stop(stopped);
        runtime.update(10_000L);
        assertThat(spawnedNow(stopped, 2)).containsExactly(0, 0);
        runtime.update(10_000L);
    }

    @Test
    void theSameSpawnsOnAnyThreadCount() throws Exception {
        List<List<String>> runs = new ArrayList<>();
        try (JobSystem one = new JobSystem(1); JobSystem four = new JobSystem(4)) {
            for (JobSystem jobs : new JobSystem[] {null, one, four}) {
                Effects store = new Effects(scene);
                EffectRuntime r = new EffectRuntime(store, jobs);
                EffectType capped = EffectType.builder("capped").maxInstances(40).maxDistance(400)
                    .lod(100, 0.5f, 2, 0.5f).build();
                EffectData data = EffectData.builder(capped)
                    .emitter("a", e -> e.spawn(Spawn.rate(Bind.random(5, 50))).duration(0.5).loops(0))
                    .emitter("b", e -> e.duration(0.3).loops(0).capacity(64).spawn(Spawn.burst(0.1, Bind.random(1, 8))))
                    .build();
                for (int i = 0; i < 100; i++) {
                    store.play(data, i * 5.0, 0, 0);
                }
                r.addViewer(0, 0, 0);
                List<String> seen = new ArrayList<>();
                for (int frame = 0; frame < 30; frame++) {
                    r.update(frame % 7 == 0 ? 50_000L : 16_667L);
                    SpawnRecords s = r.spawns();
                    for (int i = 0; i < s.size(); i++) {
                        seen.add(s.effect(i) + "/" + s.emitter(i) + ":" + s.count(i) + "@" + s.firstIndex(i) + " "
                            + s.age(i) + " " + s.interval(i));
                    }
                }
                runs.add(seen);
            }
        }
        assertThat(runs.get(0)).isNotEmpty();
        assertThat(runs.get(1)).isEqualTo(runs.get(0));
        assertThat(runs.get(2)).isEqualTo(runs.get(0));
    }

    @Test
    void updatingAllocatesNothing() throws Exception {
        try (JobSystem jobs = new JobSystem(3)) {
            EffectRuntime parallel = new EffectRuntime(effects, jobs);
            EffectType near = EffectType.builder("near").maxInstances(30).maxDistance(300).lod(60, 0.5f, 2, 0.5f)
                .build();
            EffectData data = EffectData.builder(near)
                .emitter("a", e -> e.spawn(Spawn.rate(30)).init(Init.lifetime(1)))
                .emitter("b", e -> e.duration(0.5).loops(0).capacity(20).spawn(Spawn.burst(0.1, Bind.random(2, 6))))
                .build();
            for (int i = 0; i < 50; i++) {
                effects.play(data, i * 4.0, 0, 0);
            }
            parallel.addViewer(0, 0, 0);
            for (int i = 0; i < 10; i++) {
                parallel.update(16_667L);
            }
            assertThat(parallel.spawns().size()).isPositive();
            assertAllocationFree(() -> parallel.update(16_667L), 2_000, 500, 10);
        }
    }

    @Test
    void timeStepsAndQueriesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.update(-1.0));
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.update(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.update(Double.POSITIVE_INFINITY));
        Effect e = effects.play(EffectsTest.fire(), 0, 0, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.culled(e.id())).withMessageContaining("updated");
        // Many burst calls and many effect types.
        for (int i = 0; i < 20; i++) {
            effects.burst(EffectsTest.puff(), i * 100.0, 0, 0, 0, 1, 0, i);
        }
        for (int i = 0; i < 5; i++) {
            effects.play(EffectData.builder(EffectType.builder("type " + i).build())
                .emitter("e", b -> b.spawn(Spawn.rate(1))).build(), 0, 0, 0);
        }
        runtime.update(0.5);
        assertThat(runtime.spawned(e.id(), 0)).isEqualTo(5);
        try (JobSystem jobs = new JobSystem(2)) {
            EffectRuntime few = new EffectRuntime(new Effects(scene), jobs);
            few.update(0.5); // too few effects to share out
            assertThat(few.spawns().size()).isZero();
        }
        Effect later = effects.play(EffectsTest.fire(), 0, 0, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.culled(later.id()));
        runtime.update(0.5);
        later.kill();
        Effect again = effects.play(EffectsTest.fire(), 0, 0, 0);
        assertThat((int) again.id()).isEqualTo((int) later.id());
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.culled(again.id()));
        assertThat(EffectRuntime.active(timed().emitters().get(1), 10_000_000)).as("three loops of a second")
            .isEqualTo(3_000_000);
    }
}
