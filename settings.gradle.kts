pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cleanyourtv-app"

// Every module of the module graph (ARCHITECTURE 1.2 and PRODUCT-SPEC 6). Layers and allowed
// edges live in build-logic ModuleRules.kt and are enforced by checkModuleGraph.

// Pure Kotlin JVM modules
include(":core:model")
include(":core:spec")
include(":core:ops")
include(":core:probe")
include(":core:planner")
include(":core:engine")
include(":core:trust")
include(":core:registry")
include(":core:backup")
include(":core:flows")
include(":adb:wire")
include(":adb:pairing")
include(":adb:session")

// Test-only modules
include(":testing:fixtures")
include(":testing:fake-adbd")
include(":e2e")

// Android edge modules
include(":android:platform")
include(":android:work")
include(":ui:design")
include(":feature:connect")
include(":feature:setup")
include(":feature:manage")
include(":feature:launcher")
include(":feature:modules")
include(":feature:backup")
include(":app")
include(":benchmark")
