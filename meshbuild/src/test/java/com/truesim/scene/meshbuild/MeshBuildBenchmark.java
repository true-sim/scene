package com.truesim.scene.meshbuild;

import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Build costs on a large mesh: a bumpy 512 x 512 grid (524,288 triangles, 263,169 vertices), as flat meshlets, a
 * cluster hierarchy and discrete LODs. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :scene-meshbuild:test --tests '*MeshBuildBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class MeshBuildBenchmark {

    private static final int N = 512;

    @Test
    void buildALargeMesh() {
        float[] positions = new float[(N + 1) * (N + 1) * 3];
        for (int z = 0; z <= N; z++) {
            for (int x = 0; x <= N; x++) {
                int v = z * (N + 1) + x;
                positions[3 * v] = x;
                positions[3 * v + 1] = (float) (2 * Math.sin(x / 7.0) * Math.cos(z / 11.0) + Math.sin(x * z / 900.0));
                positions[3 * v + 2] = z;
            }
        }
        int[] indices = new int[N * N * 6];
        int at = 0;
        for (int z = 0; z < N; z++) {
            for (int x = 0; x < N; x++) {
                int a = z * (N + 1) + x;
                int[] quad = {a, a + N + 1, a + 1, a + 1, a + N + 1, a + N + 2};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        MeshBuilder builder = new MeshBuilder(VertexFormat.POSITIONS_ONLY, (N + 1) * (N + 1)).positions(positions)
            .submesh(indices);
        builder.buildFlat(); // warm up
        long t0 = System.nanoTime();
        MeshData flat = builder.buildFlat();
        long t1 = System.nanoTime();
        MeshData hierarchy = builder.buildHierarchy();
        long t2 = System.nanoTime();
        MeshData discrete = builder.buildDiscrete(6, 0.5);
        long t3 = System.nanoTime();
        System.out.printf(Locale.ROOT, "Mesh build benchmark: %,d triangles, %,d vertices%n", N * N * 2,
            (N + 1) * (N + 1));
        System.out.printf(Locale.ROOT, "  flat: %.0f ms, %,d meshlets, %.1f MB%n", (t1 - t0) / 1e6,
            flat.meshletCount(), flat.bytes() / 1e6);
        System.out.printf(Locale.ROOT, "  hierarchy: %.0f ms, %,d meshlets in %d levels, %.1f MB%n", (t2 - t1) / 1e6,
            hierarchy.meshletCount(), hierarchy.levels().size(), hierarchy.bytes() / 1e6);
        StringBuilder levels = new StringBuilder();
        for (MeshLevel level : hierarchy.levels()) {
            levels.append(String.format(Locale.ROOT, " %d (%.3f)", level.meshletCount(), level.error()));
        }
        System.out.println("    meshlets per level, coarsest first (error):" + levels);
        System.out.printf(Locale.ROOT, "  discrete LODs: %.0f ms, %d levels, %,d meshlets, %.1f MB%n",
            (t3 - t2) / 1e6, discrete.levels().size(), discrete.meshletCount(), discrete.bytes() / 1e6);
    }
}
