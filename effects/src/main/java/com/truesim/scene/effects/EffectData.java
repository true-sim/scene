package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An effect as data (E3): its emitters, its user parameters with their defaults, and its {@link EffectType}. No
 * shader code: emitters list modules by name, which the engine's registry resolves, as materials name their type.
 * Built by {@link #builder}, which checks everything that cannot work (unknown modules and fields with suggestions,
 * modules on a target that lacks them, event cycles and nesting, capacities below demand) and throws
 * {@link InvalidEffectException} with every problem. Immutable, compared by value; {@link #ref()} makes it an asset.
 *
 * <pre>{@code
 * EffectData sparks = EffectData.builder(IMPACTS)
 *     .parameter(INTENSITY, 1)
 *     .emitter("sparks", e -> e.duration(0.2).spawn(Spawn.burst(0, Bind.param(INTENSITY).times(150)))
 *         .init(Shape.cone(0.5f, 0.02f), Init.lifetime(Bind.random(0.3f, 0.8f)), Init.speed(Bind.random(4, 11)))
 *         .update(Forces.gravity(), Forces.drag(0.6f), Over.size(SHRINK))
 *         .event(ParticleEvent.onDeath("embers", Bind.constant(1)).chance(0.3f))
 *         .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive()))
 *     .emitter("embers", e -> e.capacity(200).spawn(Spawn.fromEvents()).init(Init.lifetime(Bind.random(0.8f, 1.5f))))
 *     .build();
 * AssetRef<EffectData> SPARKS = sparks.ref();
 * }</pre>
 */
@AllowAllocation("effect data is made when effects are set up")
public final class EffectData {

    /** How many levels of events may nest by default: each costs a frame. */
    public static final int DEFAULT_EVENT_DEPTH = 4;

    /** Makes an {@link #ref inline} effect: the data itself. */
    private static final Generator<EffectData, EffectData> INLINE = (data, seed) -> data;

    private final EffectType type;
    private final List<EffectParameter> parameters;
    private final int[] offsets;
    private final float[] defaults;
    private final List<EmitterData> emitters;
    private final int maxEventDepth;
    private final int[] depths;
    private final double[] chains;
    private final double[] ends;
    private final double end;
    private final double lingering;
    private final double burstEnd;
    private final int variants;
    private final double near;
    private final String refusal;
    private final List<Object> key;
    private final int hash;

    private EffectData(Builder b, List<EmitterData> emitters, int[] depths, double[] chains, double[] ends,
            String refusal) {
        type = b.type;
        this.refusal = refusal;
        variants = refusal == null ? b.variants : 0;
        near = b.near;
        parameters = List.copyOf(b.parameters);
        offsets = new int[parameters.size()];
        int size = 0;
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = size;
            size += parameters.get(i).size();
        }
        defaults = new float[size];
        for (int i = 0; i < offsets.length; i++) {
            System.arraycopy(b.defaults.get(i), 0, defaults, offsets[i], parameters.get(i).size());
        }
        this.emitters = List.copyOf(emitters);
        maxEventDepth = b.maxEventDepth;
        this.depths = depths;
        this.chains = chains;
        this.ends = ends;
        double latest = Double.NEGATIVE_INFINITY;
        double longest = 0;
        for (int i = 0; i < ends.length; i++) {
            latest = Math.max(latest, ends[i]);
            longest = Math.max(longest, chains[i]);
        }
        end = Math.max(0, latest);
        lingering = longest;
        double lastBurst = 0;
        for (EmitterData e : emitters) {
            for (Spawn spawn : e.spawns()) {
                if (spawn.kind() == Spawn.Kind.BURST) {
                    lastBurst = Math.max(lastBurst, e.delay() + spawn.time());
                }
            }
        }
        burstEnd = lastBurst + lingering;
        List<Float> values = new ArrayList<>();
        for (float value : defaults) {
            values.add(value);
        }
        key = List.of(type, parameters, values, this.emitters, maxEventDepth, b.variants, near);
        hash = key.hashCode();
    }

    /** Sets up an effect of type {@code type}. */
    public static Builder builder(EffectType type) {
        return new Builder(type);
    }

    /** Sets up an effect of the {@link EffectType#DEFAULT default type}. */
    public static Builder builder() {
        return new Builder(EffectType.DEFAULT);
    }

    /** Its budget class. */
    public EffectType type() {
        return type;
    }

    /** Its user parameters, in the order declared. */
    public List<EffectParameter> parameters() {
        return parameters;
    }

    /** The index of {@code parameter} among its parameters, or -1 if it has none equal to it. Allocates nothing. */
    public int parameterIndex(EffectParameter parameter) {
        for (int i = 0; i < offsets.length; i++) {
            if (parameters.get(i).equals(parameter)) {
                return i;
            }
        }
        return -1;
    }

    /** Where parameter {@code index}'s value starts in a block of {@link #parameterFloats()} floats. */
    public int parameterOffset(int index) {
        return offsets[index];
    }

    /** Where the parameter called {@code name} starts in a parameter block, or -1 if it has none. */
    public int parameterOffset(String name) {
        for (int i = 0; i < offsets.length; i++) {
            if (parameters.get(i).name().equals(name)) {
                return offsets[i];
            }
        }
        return -1;
    }

    /** How many floats its parameters' values take, all together. */
    public int parameterFloats() {
        return defaults.length;
    }

    /** Copies the parameters' defaults into {@code block[offset..offset + parameterFloats())}. */
    public void defaults(float[] block, int offset) {
        System.arraycopy(defaults, 0, block, offset, defaults.length);
    }

    /** Its emitters, in the order declared. */
    public List<EmitterData> emitters() {
        return emitters;
    }

    /** The index of its emitter called {@code name}, or -1 if it has none. */
    public int emitter(String name) {
        for (int i = 0; i < emitters.size(); i++) {
            if (emitters.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** How many levels of events may nest in it. */
    public int maxEventDepth() {
        return maxEventDepth;
    }

    /** How many levels of events emitter {@code emitter} is below an emitter spawning on its own: 0 for those. */
    public int eventDepth(int emitter) {
        return depths[emitter];
    }

    /**
     * When emitter {@code emitter}'s last particle dies at the latest, in seconds after the effect starts, if it is
     * never stopped: infinite for emitters spawning forever.
     */
    public double emitterEnd(int emitter) {
        return ends[emitter];
    }

    /**
     * How long emitter {@code emitter}'s particles live on at most once spawning stops: its longest lifetime, plus
     * those of the emitters whose events spawn it.
     */
    public double emitterLingering(int emitter) {
        return chains[emitter];
    }

    /** When its last particle dies at the latest, in seconds after it starts, if never stopped; maybe infinite. */
    public double end() {
        return end;
    }

    /** How long its particles live on at most once it stops, in seconds. */
    public double lingering() {
        return lingering;
    }

    /**
     * When a shared instance ends at the latest after a burst into it ({@link Effects#burst}), in seconds: its last
     * burst after its delay, and the lingering of its particles.
     */
    public double burstEnd() {
        return burstEnd;
    }

    /** When it ends at the latest if stopped {@code stoppedAt} seconds after it started. */
    public double end(double stoppedAt) {
        return Math.min(end, stoppedAt + lingering);
    }

    /**
     * A reference to this data as an effect asset of its own: equal data gives equal references, so effects played
     * from equal data share one asset on the GPU.
     */
    public AssetRef<EffectData> ref() {
        return AssetRef.generated(EffectAssets.EFFECT, INLINE, this, 0);
    }

    /**
     * How many variants its instances share (E23, effects.md §2.11): each simulated once, every instance played a
     * placement drawing one of them; 0 when it is not shared, as asked for or because its modules refuse it
     * ({@link #sharingRefusal()}).
     */
    public int variants() {
        return variants;
    }

    /** Within this distance of a viewer a placement of it simulates on its own, in metres; 0: never. */
    public double nearDistance() {
        return near;
    }

    /**
     * Why it was asked to be shared and is not (its instances then simulate on their own, as unshared ones do), or
     * null: its first module, renderer or space that depends on where an instance is.
     */
    public String sharingRefusal() {
        return refusal;
    }

    /** About the memory it takes, for the asset cache's budget. */
    public long bytes() {
        long bytes = 256 + 4L * defaults.length;
        for (EmitterData e : emitters) {
            bytes += 256 + 64L * (e.init().size() + e.update().size() + e.spawns().size() + e.events().size()
                + e.renderers().size());
        }
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof EffectData d && key.equals(d.key);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "effect of type " + type.name() + " with " + emitters.size() + " emitters";
    }

    /** Sets up an {@link EffectData}. */
    @AllowAllocation("effect data is made when effects are set up")
    public static final class Builder {
        private final EffectType type;
        private final List<EffectParameter> parameters = new ArrayList<>();
        private final List<float[]> defaults = new ArrayList<>();
        private final List<EmitterData.Builder> emitters = new ArrayList<>();
        private int maxEventDepth = DEFAULT_EVENT_DEPTH;
        private int variants;
        private double near;

        private Builder(EffectType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        /** A float user parameter, {@code fallback} until an effect sets it. */
        public Builder parameter(EffectParameter.Float parameter, float fallback) {
            parameters.add(Objects.requireNonNull(parameter, "parameter"));
            defaults.add(new float[] {fallback});
            return this;
        }

        /** A color user parameter, the linear RGBA color given until an effect sets it. */
        public Builder parameter(EffectParameter.Color parameter, float r, float g, float b, float a) {
            parameters.add(Objects.requireNonNull(parameter, "parameter"));
            defaults.add(new float[] {r, g, b, a});
            return this;
        }

        /** An emitter called {@code name}, set up by {@code setup}. */
        public Builder emitter(String name, Consumer<EmitterData.Builder> setup) {
            EmitterData.Builder e = new EmitterData.Builder(name);
            setup.accept(e);
            emitters.add(e);
            return this;
        }

        /**
         * An emitter called {@code name} with {@code template}'s settings (from any effect), changed by
         * {@code overrides}: lists (spawns, modules, events, renderers) are added to.
         */
        public Builder emitter(String name, EmitterData template, Consumer<EmitterData.Builder> overrides) {
            EmitterData.Builder e = new EmitterData.Builder(name, template);
            overrides.accept(e);
            emitters.add(e);
            return this;
        }

        /** Allows {@code levels} levels of events to nest; {@value #DEFAULT_EVENT_DEPTH} by default. */
        public Builder maxEventDepth(int levels) {
            if (levels < 1) {
                throw new IllegalArgumentException("Not a depth: " + levels);
            }
            maxEventDepth = levels;
            return this;
        }

        /**
         * Shares simulations among its instances (E23): {@code variants} simulations, each at its own seed, drawn at
         * every instance played (a placement: a transform and a variant), if its modules allow it: every emitter on the
         * GPU in local space, no collision, mesh or bone shapes, mesh, light or beam renderers. Otherwise it is
         * simulated per instance, as {@link EffectData#sharingRefusal()} says why. Placements within
         * {@code nearDistance} metres of a viewer (0: none) simulate on their own.
         */
        public Builder shared(int variants, double nearDistance) {
            if (variants < 1 || !(nearDistance >= 0)) {
                throw new IllegalArgumentException("Not a sharing: " + variants + " variants, near " + nearDistance);
            }
            this.variants = variants;
            near = nearDistance;
            return this;
        }

        /** Shares {@code variants} simulations among its instances, however near ({@link #shared(int, double)}). */
        public Builder shared(int variants) {
            return shared(variants, 0);
        }

        /** The effect, its modules checked against the built-in modules. */
        public EffectData build() {
            return build(ModuleRegistry.BUILT_IN);
        }

        /** The effect, its modules checked against {@code modules}. */
        public EffectData build(ModuleRegistry modules) {
            return new Check(this, modules).run();
        }
    }

    /** Checks a builder's data and settles what follows from it: capacities, lifetimes, event depths and ends. */
    @AllowAllocation("effect data is made when effects are set up")
    private static final class Check {
        private final Builder b;
        private final ModuleRegistry modules;
        private final List<String> problems = new ArrayList<>();
        private final Map<String, EffectParameter> parameters = new LinkedHashMap<>();
        private final Map<String, Integer> emitters = new LinkedHashMap<>();
        private final int n;
        /** The emitters whose events spawn each emitter. */
        private final List<List<Integer>> parents = new ArrayList<>();

        Check(Builder b, ModuleRegistry modules) {
            this.b = b;
            this.modules = modules;
            n = b.emitters.size();
        }

        EffectData run() {
            for (EffectParameter p : b.parameters) {
                if (parameters.putIfAbsent(p.name(), p) != null) {
                    problems.add("Two parameters are called " + p.name() + ".");
                }
            }
            for (int i = 0; i < n; i++) {
                parents.add(new ArrayList<>());
                if (emitters.putIfAbsent(b.emitters.get(i).name, i) != null) {
                    problems.add("Two emitters are called " + b.emitters.get(i).name + ".");
                }
            }
            float[] lifetimes = new float[n];
            int[] capacities = new int[n];
            Capability[] capabilities = new Capability[n];
            for (int i = 0; i < n; i++) {
                EmitterData.Builder e = b.emitters.get(i);
                Capability init = modules(e, e.initializers(), ModuleType.Stage.INIT);
                Capability update = modules(e, e.updaters(), ModuleType.Stage.UPDATE);
                capabilities[i] = init.compareTo(update) >= 0 ? init : update;
                lifetimes[i] = lifetime(e);
                capacities[i] = capacity(e, lifetimes[i]);
                events(i, e);
            }
            int[] depths = new int[n];
            if (!cycle()) {
                for (int i = 0; i < n; i++) {
                    depths[i] = depth(i);
                    if (depths[i] > b.maxEventDepth) {
                        problems.add(b.emitters.get(i).name + " is " + depths[i] + " levels of events deep, more than"
                            + " the " + b.maxEventDepth + " allowed.");
                    }
                }
            }
            if (!problems.isEmpty()) {
                throw new InvalidEffectException(problems);
            }
            List<EmitterData> built = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                built.add(new EmitterData(b.emitters.get(i), capacities[i], lifetimes[i], capabilities[i]));
            }
            double[] chains = new double[n];
            double[] ends = new double[n];
            for (int i = 0; i < n; i++) {
                settle(i, built, chains, ends);
            }
            return new EffectData(b, built, depths, chains, ends, b.variants == 0 ? null : refusal(built));
        }

        /** Why the effect cannot share simulations (effects.md §2.11's eligibility), or null. */
        private static String refusal(List<EmitterData> built) {
            for (EmitterData e : built) {
                if (e.target() != Target.GPU) {
                    return e.name() + " is a CPU emitter";
                }
                if (e.space() != Space.LOCAL) {
                    return e.name() + " is in world space";
                }
                for (List<Module> list : List.of(e.init(), e.update())) {
                    for (Module module : list) {
                        String name = module.type();
                        if (name.startsWith("collide.") || name.equals(Shape.MESH) || name.equals(Shape.BONES)) {
                            return e.name() + " has " + name + ", which depends on where it is";
                        }
                    }
                }
                for (Renderer r : e.renderers()) {
                    if (r.kind() == Renderer.Kind.MESH || r.kind() == Renderer.Kind.LIGHT
                        || r.kind() == Renderer.Kind.BEAM) {
                        return e.name() + " draws " + r.kind().name().toLowerCase() + "s, which are per instance";
                    }
                }
            }
            return null;
        }

        /** Checks modules at {@code stage}; returns what they need of the device. */
        private Capability modules(EmitterData.Builder e, List<Module> list, ModuleType.Stage stage) {
            Capability needed = Capability.NONE;
            for (Module module : list) {
                ModuleType type = modules.type(module.type());
                if (type == null) {
                    problems.add(e.name + ": no module is called " + module.type()
                        + Suggestions.didYouMean(module.type(), modules.names()) + ".");
                    continue;
                }
                if (type.stage() != stage) {
                    problems.add(e.name + ": " + module.type() + " runs at " + type.stage() + ", not at " + stage
                        + ".");
                }
                if (!type.runsOn(e.target()) && !chained(list, module, e.target())) {
                    problems.add(e.name + ": " + module.type() + " does not run on " + e.target() + " emitters.");
                }
                if (type.capability().compareTo(needed) > 0) {
                    needed = type.capability();
                }
                for (ModuleType.Field field : type.fields().values()) {
                    if (field.kind() == ModuleType.Kind.ASSET && module.value(field.name()) == null) {
                        problems.add(e.name + ": " + module.type() + " needs its " + field.name() + ".");
                    }
                }
                for (Map.Entry<String, Object> value : module.values().entrySet()) {
                    ModuleType.Field field = type.fields().get(value.getKey());
                    String where = e.name + ": " + module.type() + "." + value.getKey();
                    if (field == null) {
                        problems.add(e.name + ": " + module.type() + " has no field " + value.getKey()
                            + Suggestions.didYouMean(value.getKey(), type.fields().keySet()) + ".");
                    } else if (ModuleType.Kind.of(value.getValue()) != field.kind()) {
                        problems.add(where + " takes a " + field.kind() + ", not a "
                            + ModuleType.Kind.of(value.getValue()) + ".");
                    } else {
                        value(where, value.getValue());
                    }
                }
            }
            return needed;
        }

        /**
         * Whether {@code module} is in a collision chain of {@code list} (a method and its fallbacks) of which some
         * method runs on {@code target}: the others are left out on that target.
         */
        private boolean chained(List<Module> list, Module module, Target target) {
            int at = list.indexOf(module);
            int first = at;
            while (first > 0 && Collide.fallback(list.get(first))) {
                first--;
            }
            int last = at;
            while (last + 1 < list.size() && Collide.fallback(list.get(last + 1))) {
                last++;
            }
            for (int i = first; i <= last && last > first; i++) {
                ModuleType type = modules.type(list.get(i).type());
                if (type != null && type.runsOn(target)) {
                    return true;
                }
            }
            return false;
        }

        /** Checks the parameter a module value reads. */
        private void value(String where, Object value) {
            if (value instanceof Binding binding) {
                binding(where, binding);
            } else if (value instanceof ColorBinding color && color.parameter() != null) {
                parameter(where, color.parameter(), EffectParameter.Color.class);
            }
        }

        private void binding(String where, Binding binding) {
            if (binding.parameter() != null) {
                parameter(where, binding.parameter(), EffectParameter.Float.class);
            }
        }

        private void parameter(String where, String name, Class<? extends EffectParameter> kind) {
            EffectParameter p = parameters.get(name);
            if (p == null) {
                problems.add(where + " reads no parameter of the effect: " + name
                    + Suggestions.didYouMean(name, parameters.keySet()) + ".");
            } else if (!kind.isInstance(p)) {
                problems.add(where + " reads " + name + ", which is not a " + kind.getSimpleName().toLowerCase() + ".");
            }
        }

        /** The value of float parameter {@code name} by default, 0 if there is none. */
        private float fallback(String name) {
            int i = b.parameters.indexOf(parameters.get(name));
            return i >= 0 && b.parameters.get(i) instanceof EffectParameter.Float ? b.defaults.get(i)[0] : 0;
        }

        /** The longest lifetime of an emitter's particles, from its last lifetime initializer. */
        private float lifetime(EmitterData.Builder e) {
            float lifetime = EmitterData.DEFAULT_LIFETIME;
            for (Module module : e.initializers()) {
                if (module.type().equals(Init.LIFETIME) && module.value("lifetime") instanceof Binding binding) {
                    lifetime = max(binding);
                }
            }
            return lifetime;
        }

        private float max(Binding binding) {
            return Math.max(0, binding.max(binding.parameter() == null ? 0 : fallback(binding.parameter())));
        }

        /** Checks how an emitter spawns; returns its capacity, as authored or estimated. */
        private int capacity(EmitterData.Builder e, float lifetime) {
            long demand = 0;
            boolean unbounded = false;
            for (Spawn spawn : e.spawns()) {
                String where = e.name + ": " + spawn.kind().name().toLowerCase();
                if (spawn.amount() != null) {
                    binding(where, spawn.amount());
                    if (!spawn.amount().kind().perInstance()) {
                        problems.add(where + " is bound by " + spawn.amount().kind() + ", which differs per particle.");
                    }
                }
                switch (spawn.kind()) {
                    case RATE -> demand += (long) Math.ceil(max(spawn.amount()) * lifetime);
                    case BURST -> {
                        if (spawn.time() >= e.duration()) {
                            problems.add(where + " at " + spawn.time() + " s is after the end of its loop at "
                                + e.duration() + " s.");
                        }
                        long alive = e.duration() == Double.POSITIVE_INFINITY ? 1
                            : (long) Math.ceil(lifetime / e.period());
                        demand += (long) Math.ceil(max(spawn.amount())) * (e.loops() == 0 ? alive
                            : Math.min(e.loops(), alive));
                    }
                    default -> unbounded = true;
                }
            }
            int authored = e.capacity();
            if (unbounded && authored == 0) {
                problems.add(e.name + " needs a capacity: it spawns per distance or from events.");
            } else if (authored != 0 && authored < demand) {
                problems.add(e.name + ": its capacity of " + authored + " is below its demand of " + demand
                    + " (rates times lifetime, plus bursts).");
            } else if (!unbounded && demand == 0) {
                problems.add(e.name + " spawns nothing.");
            }
            return authored != 0 ? authored : (int) Math.min(demand, Integer.MAX_VALUE);
        }

        /** Checks the events an emitter sends, noting the emitters they spawn. */
        private void events(int i, EmitterData.Builder e) {
            long emits = e.events().stream().filter(event -> event.kind() == ParticleEvent.Kind.EMIT).count();
            if (emits > CpuSimulation.MAX_EMITS) {
                problems.add(e.name + " sends " + emits + " emit events, more than the " + CpuSimulation.MAX_EMITS
                    + " a particle keeps accumulators for.");
            }
            for (ParticleEvent event : e.events()) {
                String where = e.name + ": " + event.kind().name().toLowerCase() + " event";
                binding(where, event.count());
                Integer target = emitters.get(event.target());
                if (target == null) {
                    problems.add(where + " spawns no emitter of the effect: " + event.target()
                        + Suggestions.didYouMean(event.target(), emitters.keySet()) + ".");
                    continue;
                }
                EmitterData.Builder t = b.emitters.get(target);
                if (!t.spawnsFromEvents()) {
                    problems.add(where + " spawns " + t.name + ", which does not spawn from events.");
                }
                if (e.target() == Target.GPU && t.target() == Target.CPU) {
                    problems.add(where + ": a GPU emitter cannot spawn into the CPU emitter " + t.name + ".");
                }
                if (e.target() == Target.CPU && t.target() == Target.GPU) {
                    problems.add(where + ": a CPU emitter spawns into CPU emitters only, not " + t.name + ".");
                }
                parents.get(target).add(i);
            }
        }

        /** Whether events go round in a cycle, noting the first found. */
        private boolean cycle() {
            int[] state = new int[n]; // 0 unvisited, 1 on the path, 2 done
            for (int i = 0; i < n; i++) {
                List<Integer> cycle = state[i] == 0 ? cycle(i, state, new ArrayList<>()) : null;
                if (cycle != null) {
                    StringBuilder s = new StringBuilder("Events go round in a cycle: ");
                    for (int k = cycle.size() - 1; k > 0; k--) {
                        s.append(b.emitters.get(cycle.get(k)).name).append(" -> ");
                    }
                    problems.add(s.append(b.emitters.get(cycle.get(0)).name).append('.').toString());
                    return true;
                }
            }
            return false;
        }

        /**
         * Walks from {@code i} up to the emitters spawning it, {@code path} the way there; returns a cycle found, each
         * emitter followed by one spawning it, the first last again; or null.
         */
        private List<Integer> cycle(int i, int[] state, List<Integer> path) {
            state[i] = 1;
            path.add(i);
            for (int parent : parents.get(i)) {
                if (state[parent] == 1) {
                    List<Integer> cycle = new ArrayList<>(path.subList(path.indexOf(parent), path.size()));
                    cycle.add(parent);
                    return cycle;
                }
                List<Integer> cycle = state[parent] == 0 ? cycle(parent, state, path) : null;
                if (cycle != null) {
                    return cycle;
                }
            }
            path.remove(path.size() - 1);
            state[i] = 2;
            return null;
        }

        private int depth(int i) {
            int depth = 0;
            for (int parent : parents.get(i)) {
                depth = Math.max(depth, depth(parent) + 1);
            }
            return depth;
        }

        /** Settles emitter {@code i}'s lingering and end, after those of the emitters spawning it. */
        private void settle(int i, List<EmitterData> built, double[] chains, double[] ends) {
            EmitterData e = built.get(i);
            double chain = 0;
            double end = Double.NEGATIVE_INFINITY;
            for (int parent : parents.get(i)) {
                settle(parent, built, chains, ends);
                chain = Math.max(chain, chains[parent]);
                end = Math.max(end, ends[parent] + e.maxLifetime());
            }
            for (Spawn spawn : e.spawns()) {
                if (spawn.kind() != Spawn.Kind.EVENTS) {
                    end = Math.max(end, e.spawnEnd() + e.maxLifetime());
                }
            }
            chains[i] = chain + e.maxLifetime();
            ends[i] = end;
        }
    }
}
