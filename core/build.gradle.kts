plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene core for true-sim: node handles, the transform hierarchy, bounds, layers and change tracking."

dependencies {
    api("com.truesim.common:common-math:0.4.0")
    api("com.truesim.common:common-state:0.2.0")
    api("com.truesim.common:common-jobs:0.3.2")
}

coverage {
    minimum = 1.0
}

// The benchmark (TRUESIM_BENCHMARK=true) holds a million nodes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}
