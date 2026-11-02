plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene audio for true-sim: clips, sounds, buses and snapshots as data, the sounds playing in a scene and music logic; no native code, no DSP."

dependencies {
    api(project(":scene-core"))
    api(project(":scene-assets"))
}

coverage {
    minimum = 1.0
}
