plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene world for true-sim: cells, data layers with levels, loaders and streaming on common-jobs lanes."

dependencies {
    api(project(":scene-core"))
    api("com.truesim.common:common-jobs:0.3.0")
}

coverage {
    minimum = 1.0
}

// The benchmark (TRUESIM_BENCHMARK=true) streams a large grid of cells.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
    }
}
