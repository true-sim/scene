package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * A skin bound to scene nodes: the joints that deform a renderable's mesh (its {@code MeshData} joint palette names them
 * by index here) and each joint's inverse bind matrix (16 floats, column-major, as glTF's: from the mesh's space to the
 * joint's at rest). The renderer poses the mesh by the joints' world transforms relative to the renderable's node.
 * {@link ModelData#spawn} binds a model's skins. Immutable.
 */
public final class SkinBinding {

    private final long[] joints;
    private final float[] inverseBindMatrices;

    /** Joints {@code joints} (scene nodes) with {@code inverseBindMatrices}, 16 floats each. */
    @AllowAllocation("bound when models spawn")
    public SkinBinding(long[] joints, float[] inverseBindMatrices) {
        if (joints.length == 0 || inverseBindMatrices.length != 16 * joints.length) {
            throw new IllegalArgumentException("A skin of joints and 16 floats per joint: " + joints.length + ", "
                + inverseBindMatrices.length);
        }
        this.joints = joints.clone();
        this.inverseBindMatrices = inverseBindMatrices.clone();
    }

    public int jointCount() {
        return joints.length;
    }

    /** The scene node of joint {@code joint}. */
    public long joint(int joint) {
        return joints[joint];
    }

    /** Element {@code element} (column-major, 0 to 15) of joint {@code joint}'s inverse bind matrix. */
    public float inverseBind(int joint, int element) {
        return inverseBindMatrices[16 * joint + Objects.checkIndex(element, 16)];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SkinBinding other && Arrays.equals(joints, other.joints)
            && Arrays.equals(inverseBindMatrices, other.inverseBindMatrices);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(joints) + Arrays.hashCode(inverseBindMatrices);
    }
}
