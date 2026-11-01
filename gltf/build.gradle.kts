plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene glTF importer for true-sim: glTF 2.0 and GLB files into models, meshes, materials and textures."

val lwjglVersion = "3.4.3"
val lwjglNatives = listOf(
    "natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64", "natives-macos",
    "natives-macos-arm64",
)

dependencies {
    api(project(":scene-render"))
    implementation(project(":scene-meshbuild"))
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl-meshoptimizer") // meshopt-compressed buffers
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl-meshoptimizer::$natives")
    }
}

coverage {
    minimum = 1.0
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The Khronos sample models, swept when given: ./gradlew :scene-gltf:test -PgltfSamples=<glTF-Sample-Assets/Models>
tasks.test {
    providers.gradleProperty("gltfSamples").orNull?.let {
        systemProperty("truesim.gltf.samples", it)
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
