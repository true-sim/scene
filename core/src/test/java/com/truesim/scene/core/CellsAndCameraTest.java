package com.truesim.scene.core;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Mat4;
import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.TransformArray;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.AabbArray;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CellsAndCameraTest {

    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // ---------------------------------------------------------------- cells

    @Test
    void positionsMapToCells() {
        CellGrid grid = new CellGrid(128);
        assertThat(grid.size()).isEqualTo(128);
        assertThat(grid.cell(0)).isZero();
        assertThat(grid.cell(127.999)).isZero();
        assertThat(grid.cell(128)).isEqualTo(1);
        assertThat(grid.cell(-0.001)).isEqualTo(-1);
        assertThat(grid.cell(-128)).isEqualTo(-1);
        assertThat(grid.cell(10_000_000)).isEqualTo(78_125);
        assertThat(grid.cellMin(-2)).isEqualTo(-256);
    }

    @Test
    void boxesVisitTheCellsTheyOverlap() {
        CellGrid grid = new CellGrid(10);
        List<String> cells = new ArrayList<>();
        int count = grid.forEachCell(-5, 0, 0, 10, 0, 19.5, (x, y, z) -> cells.add(x + "," + y + "," + z));
        // x: -5..10 ends on the boundary of cell 1, so cells -1 and 0; z: 0..19.5 is cells 0 and 1.
        assertThat(cells).containsExactly("-1,0,0", "0,0,0", "-1,0,1", "0,0,1");
        assertThat(count).isEqualTo(4);
        cells.clear();
        grid.forEachCell(10, 10, 10, 10, 10, 10, (x, y, z) -> cells.add(x + "," + y + "," + z)); // a point on a corner
        assertThat(cells).containsExactly("1,1,1");
    }

    @Test
    void cellsPackIntoKeys() {
        int[] values = {0, 1, -1, 12_345, -54_321, CellGrid.MIN_CELL, CellGrid.MAX_CELL};
        for (int x : values) {
            for (int y : values) {
                for (int z : values) {
                    long key = CellGrid.key(x, y, z);
                    assertThat(CellGrid.keyX(key)).isEqualTo(x);
                    assertThat(CellGrid.keyY(key)).isEqualTo(y);
                    assertThat(CellGrid.keyZ(key)).isEqualTo(z);
                }
            }
        }
        assertThat(CellGrid.key(1, 0, 0)).isNotEqualTo(CellGrid.key(0, 1, 0)).isNotEqualTo(CellGrid.key(0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> CellGrid.key(CellGrid.MAX_CELL + 1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> CellGrid.key(0, CellGrid.MIN_CELL - 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> CellGrid.key(0, 0, Integer.MAX_VALUE));
    }

    @Test
    void gridsNeedAPositiveSize() {
        for (double size : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new CellGrid(size));
        }
    }

    // ---------------------------------------------------------------- camera-relative

    private static float f(MemorySegment segment, int element) {
        return segment.get(FLOAT, (long) element * Float.BYTES);
    }

    @Test
    void matricesAreRelativeToTheCamera() {
        Transform transform = Transform.of(Vec3.of(10_000_000.25, 3, -7_500_000.5),
            Quat.fromAxisAngle(Vec3.of(1, 2, 3).normalize(), 0.9), Vec3.of(2, 0.5, 1.5));
        TransformArray transforms = TransformArray.onHeap(2);
        transforms.set(1, transform);
        Vec3 camera = Vec3.of(10_000_000, 0, -7_500_000);
        Mat4 expected = transform.withTranslation(transform.translation().sub(camera)).toMat4();

        MemorySegment matrix = MemorySegment.ofArray(new byte[CameraRelative.MATRIX_BYTES + 4]);
        CameraRelative.writeMatrix(transforms, 1, camera.x(), camera.y(), camera.z(), matrix, 4); // unaligned is fine
        MemorySegment written = matrix.asSlice(4);
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                assertThat(f(written, column * 4 + row)).as("column %d row %d", column, row)
                    .isCloseTo((float) expected.get(row, column), within(1e-5f));
            }
        }
        // Far from the origin, but exact near the camera: 0.25 m survives in float.
        assertThat(f(written, 12)).isEqualTo(0.25f);
        assertThat(f(written, 14)).isEqualTo(-0.5f);

        MemorySegment affine = MemorySegment.ofArray(new byte[CameraRelative.AFFINE_BYTES]);
        CameraRelative.writeAffine(transforms, 1, camera.x(), camera.y(), camera.z(), affine, 0);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                assertThat(f(affine, row * 4 + column)).isEqualTo(f(written, column * 4 + row));
            }
        }
    }

    @Test
    void boundsAreRelativeToTheCamera() {
        AabbArray bounds = AabbArray.onHeap(1);
        bounds.set(0, 1_000_000, -2, 5, 1_000_001.5, 2, 6);
        MemorySegment box = MemorySegment.ofArray(new byte[CameraRelative.BOUNDS_BYTES]);
        CameraRelative.writeBounds(bounds, 0, 1_000_000, 0, 5, box, 0);
        assertThat(new float[] {f(box, 0), f(box, 1), f(box, 2), f(box, 3), f(box, 4), f(box, 5)})
            .containsExactly(0, -2, 0, 1.5f, 2, 1);
    }

    @Test
    void writingAllocatesNothing() {
        TransformArray transforms = TransformArray.onHeap(1);
        transforms.set(0, Transform.IDENTITY);
        AabbArray bounds = AabbArray.onHeap(1);
        MemorySegment target = MemorySegment.ofArray(new byte[256]);
        assertAllocationFree(() -> {
            CameraRelative.writeMatrix(transforms, 0, 1, 2, 3, target, 0);
            CameraRelative.writeAffine(transforms, 0, 1, 2, 3, target, 64);
            CameraRelative.writeBounds(bounds, 0, 1, 2, 3, target, 112);
        });
    }
}
