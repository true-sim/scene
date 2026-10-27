plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene world for true-sim: cells, data layers with levels, loaders and streaming on common-jobs lanes."

dependencies {
    api(project(":scene-core"))
}

coverage {
    minimum = 1.0
}

// The benchmark (TRUESIM_BENCHMARK=true) streams a large grid of cells.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}
