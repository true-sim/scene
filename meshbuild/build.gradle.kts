plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene mesh builder for true-sim: meshlets, cluster LOD hierarchies and quantization on meshoptimizer."

val lwjglVersion = "3.4.3"
val lwjglNatives = listOf(
    "natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64", "natives-macos",
    "natives-macos-arm64",
)

dependencies {
    api(project(":scene-assets"))
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-meshoptimizer")
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$natives")
        runtimeOnly("org.lwjgl:lwjgl-meshoptimizer::$natives")
    }
}

coverage {
    minimum = 1.0
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The benchmark (TRUESIM_BENCHMARK=true) builds a half-million-triangle mesh.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}
