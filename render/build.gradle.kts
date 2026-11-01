plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene render data for true-sim: renderables on nodes, materials and textures; no drawing."

dependencies {
    api(project(":scene-core"))
    api(project(":scene-assets"))
    runtimeOnly("com.twelvemonkeys.imageio:imageio-webp:3.12.0") // WebP through ImageIO
}

coverage {
    minimum = 1.0
}
