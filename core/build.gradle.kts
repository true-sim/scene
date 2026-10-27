plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene core for true-sim: node handles, the transform hierarchy, bounds, layers and change tracking."

dependencies {
    api("com.truesim.common:common-math:0.3.0")
    api("com.truesim.common:common-state:0.2.0")
}

coverage {
    minimum = 1.0
}
