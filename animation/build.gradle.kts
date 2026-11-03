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

// The benchmark (TRUESIM_BENCHMARK=true) measures threads sampling: JaCoCo's shared probes would make them contend.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}
