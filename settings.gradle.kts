pluginManagement {
    // Develop against a local build-logic checkout: -Ptruesim.build-logic=../build-logic
    providers.gradleProperty("truesim.build-logic").orNull?.let { includeBuild(it) }
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
    id("truesim.settings") version "0.3.0"
}

rootProject.name = "scene"

// Each module is published on its own, so a dedicated server takes core and world without render data.
include("scene-core", "scene-world")
project(":scene-core").projectDir = file("core")
project(":scene-world").projectDir = file("world")

providers.gradleProperty("truesim.build-logic").orNull?.let { includeBuild(it) }

dependencyResolutionManagement {
    repositories {
        for (repo in listOf("build-logic", "common-math", "common-state", "common-jobs")) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
