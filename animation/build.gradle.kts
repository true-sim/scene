plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene animation for true-sim: clips, skeletons and animators posing scene nodes; no rendering."

dependencies {
    api(project(":scene-core"))
    api(project(":scene-assets"))
}

coverage {
    minimum = 1.0
}
