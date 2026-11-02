plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene effects for true-sim: particle effects as data, the emitter runtime and the CPU simulation; no rendering."

dependencies {
    api(project(":scene-core"))
    api(project(":scene-assets"))
    testImplementation("com.truesim.common:common-collision:0.2.0") // a CPU emitter hitting the physics scene
}

coverage {
    minimum = 1.0
}
