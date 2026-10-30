pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.7.0"
}

rootProject.name = "scene"

// Each module is published on its own, so a dedicated server takes core and world without render data.
include("scene-core", "scene-world", "scene-collision", "scene-assets", "scene-meshbuild", "scene-render")
project(":scene-core").projectDir = file("core")
project(":scene-world").projectDir = file("world")
project(":scene-collision").projectDir = file("collision")
project(":scene-assets").projectDir = file("assets")
project(":scene-meshbuild").projectDir = file("meshbuild")
project(":scene-render").projectDir = file("render")

val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "common-collision")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
