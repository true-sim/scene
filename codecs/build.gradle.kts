plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene audio codecs for true-sim: Ogg Vorbis through stb_vorbis, importers by extension, streamed clips."

val lwjglVersion = "3.4.3"
val lwjglNatives = listOf(
    "natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64", "natives-macos",
    "natives-macos-arm64",
)

dependencies {
    api(project(":scene-audio"))
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-stb")
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$natives")
        runtimeOnly("org.lwjgl:lwjgl-stb::$natives")
    }
}

coverage {
    minimum = 1.0
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
