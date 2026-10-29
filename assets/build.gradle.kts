plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene assets for true-sim: typed asset references, pluggable sources and the asset cache."

dependencies {
    api("com.truesim.common:common-jobs:0.3.2")
}

coverage {
    minimum = 1.0
}
