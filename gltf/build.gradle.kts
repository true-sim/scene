plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Scene glTF importer for true-sim: glTF 2.0 and GLB files into models, meshes, materials and textures."

dependencies {
    api(project(":scene-render"))
    implementation(project(":scene-meshbuild"))
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
