plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene render data for true-sim: renderables on nodes, materials and textures; no drawing."

dependencies {
    api(project(":scene-core"))
    api(project(":scene-assets"))
}

coverage {
    minimum = 1.0
}
