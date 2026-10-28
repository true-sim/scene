package com.truesim.scene.assets.mesh;

/**
 * One detail level of a mesh: a contiguous range of its meshlets and their simplification error, in the mesh's units
 * (0 for full detail). Levels are stored coarsest first, so a mesh streams in from its coarsest level.
 */
public record MeshLevel(int firstMeshlet, int meshletCount, double error) {
}
