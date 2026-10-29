plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene collision for true-sim: each streamed cell's colliders and terrain heights on common-collision."

dependencies {
    api(project(":scene-world"))
    api("com.truesim.common:common-collision:0.2.0")
}

coverage {
    minimum = 1.0
}
