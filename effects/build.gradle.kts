plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene effects for true-sim: particle effects as data, the emitter runtime and the CPU simulation; no rendering."

dependencies {
    api(project(":scene-core"))
    api(project(":scene-assets"))
}

coverage {
    minimum = 1.0
}
