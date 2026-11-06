package com.truesim.scene.assets.mesh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Meshes let go of, kept and built into by meshes of the same sizes. */
class MeshPoolTest {

    private static final float[] QUAD = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};

    /** A quad of one meshlet, its corner raised to {@code height}. */
    private static MeshData.Builder quad(float height) {
        float[] positions = QUAD.clone();
        positions[10] = height;
        return MeshData.builder(VertexFormat.POSITIONS_ONLY, 4).positions(positions)
            .meshlet(new int[] {0, 1, 2, 3}, new int[] {0, 1, 2, 0, 2, 3}, 0, new Sphere(0.5, 0, 0.5, 0.8), Cone.NONE);
    }

    /** Two quads side by side: other sizes than one. */
    private static MeshData.Builder twoQuads() {
        float[] positions = new float[24];
        System.arraycopy(QUAD, 0, positions, 0, 12);
        System.arraycopy(QUAD, 0, positions, 12, 12);
        for (int v = 4; v < 8; v++) {
            positions[3 * v] += 2;
        }
        return MeshData.builder(VertexFormat.POSITIONS_ONLY, 8).positions(positions)
            .meshlet(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, new int[] {0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7}, 0,
                new Sphere(1.5, 0, 0.5, 2), Cone.NONE);
    }

    private static byte[] bytes(MeshData mesh) {
        return java.util.stream.Stream.of(mesh.positions(), mesh.attributes(), mesh.meshletVertices(),
                mesh.meshletTriangles(), mesh.meshlets(), mesh.clusterLod())
            .map(s -> s.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE))
            .reduce(new byte[0], (a, b) -> {
                byte[] all = java.util.Arrays.copyOf(a, a.length + b.length);
                System.arraycopy(b, 0, all, a.length, b.length);
                return all;
            });
    }

    @Test
    void aMeshOfTheSameSizesIsBuiltIntoOneLetGoOf() {
        MeshPool pool = new MeshPool(4);
        MeshData old = quad(0).build();
        assertThat(pool.offer(old)).isNull(); // kept: nothing to free
        assertThat(pool.size()).isEqualTo(1);
        MeshData other = twoQuads().build(pool); // other sizes: built anew
        assertThat(pool.reusedCount()).isZero();
        assertThat(old.isClosed()).isFalse();
        MeshData built = quad(2).build(pool);
        assertThat(pool.reusedCount()).isEqualTo(1);
        assertThat(pool.size()).isZero();
        assertThat(old.isClosed()).as("its object handed over").isTrue();
        assertThat(built.isClosed()).isFalse();
        assertThat(built.positions().address()).isEqualTo(old.positions().address()); // the same memory
        assertThat(bytes(built)).containsExactly(bytes(quad(2).build())); // as if built anew
        old.close(); // closing the object handed over frees nothing
        assertThat(bytes(built)).containsExactly(bytes(quad(2).build()));
        built.close();
        other.close();
        assertThat(quad(1).build(null).isClosed()).isFalse(); // no pool: memory of its own
    }

    @Test
    void aFullPoolGivesBackItsOldestAndKeepsNoMorphedOrClosedMeshes() {
        MeshPool pool = new MeshPool(2);
        MeshData first = quad(0).build();
        MeshData second = quad(1).build();
        MeshData third = quad(2).build();
        assertThat(pool.offer(first)).isNull();
        assertThat(pool.offer(second)).isNull();
        assertThat(pool.offer(third)).isSameAs(first); // full: the oldest goes, for the caller to free
        assertThat(pool.capacity()).isEqualTo(2);
        MeshData closed = quad(3).build();
        closed.close();
        assertThat(pool.offer(closed)).isSameAs(closed);
        float[] lift = new float[12];
        lift[1] = 1;
        MeshData morphed = quad(0).morphTarget("lift", lift, null, null).build();
        assertThat(pool.offer(morphed)).isSameAs(morphed);
        assertThat(pool.size()).isEqualTo(2);
        pool.capacity(1); // fewer: the oldest freed now
        assertThat(second.isClosed()).isTrue();
        assertThat(third.isClosed()).isFalse();
        pool.capacity(4);
        pool.offer(twoQuads().build());
        assertThat(quad(5).build(pool).positions().address()).isEqualTo(third.positions().address());
        pool.clear();
        assertThat(pool.size()).isZero();
        MeshPool many = new MeshPool(10); // more than its list starts with room for
        List<MeshData> offered = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            offered.add(quad(i).build());
            assertThat(many.offer(offered.get(i))).isNull();
        }
        offered.get(0).close(); // closed meanwhile (wrongly, by an owner it left): never taken
        assertThat(quad(0).build(many).positions().address()).isEqualTo(offered.get(1).positions().address());
        MeshData morphedAgain = quad(0).morphTarget("lift", lift, null, null).build(many); // morphs: built anew
        assertThat(many.size()).isEqualTo(8); // the closed one stays, never taken

        morphedAgain.close();
        many.clear();
        MeshPool none = new MeshPool(0);
        MeshData refused = quad(0).build();
        assertThat(none.offer(refused)).isSameAs(refused);
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshPool(-1));
        morphed.close();
        first.close();
        refused.close();
    }
}
