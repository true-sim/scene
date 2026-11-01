package com.truesim.scene.animation;

/**
 * A joint of a rig's skeleton, found once by name ({@link Rig.Builder#joint}) and used by index from then on: masks,
 * and later inverse kinematics and overrides, take joints.
 *
 * @param index its node in the skeleton
 * @param name its name
 */
public record Joint(int index, String name) {
}
