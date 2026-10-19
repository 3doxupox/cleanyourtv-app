plugins { id("cleanyourtv.jvm") }

// W9: the TV e2e harness (ARCHITECTURE 6.6, PRODUCT-SPEC 7.1). It drives the app's own
// :adb:session, :core:probe, :core:planner and :core:engine against a real adbd on an Android TV
// emulator, with the platform's adb client as the oracle.
//
// Two test source sets:
//  - `test`: the harness's own tests (handshake, host key, helpers). No emulator; part of `check`.
//  - `emulatorTest`: the scenarios (TvE2eSuite). They need a running TV emulator, which
//    scripts/emulator-session.sh provides (CYT_E2E_* variables), so they are never part of
//    `check`: run `./gradlew :e2e:emulatorTest` inside a session (forge:
//    scripts/emulator-on-forge.sh run 28; CI: nightly.yml).
dependencies {
    implementation(project(":adb:session"))
    implementation(project(":core:engine"))
    implementation(project(":core:probe"))
    implementation(project(":testing:fixtures"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.junit4)
}

val emulatorTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[emulatorTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[emulatorTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies {
    add(emulatorTest.implementationConfigurationName, libs.apksig)   // signer digest of fixture APKs
}

tasks.register<Test>("emulatorTest") {
    description = "TV e2e scenarios against a running Android TV emulator (scripts/emulator-session.sh)."
    group = "verification"
    testClassesDirs = emulatorTest.output.classesDirs
    classpath = emulatorTest.runtimeClasspath
    filter { includeTestsMatching("tv.cleanyour.e2e.TvE2eSuite") }
    // One fork, small heap: the emulator shares the session's 3 GB with this JVM.
    maxParallelForks = 1
    maxHeapSize = "384m"
    // A scenario run is never up to date: the TV is the input.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    shouldRunAfter(tasks.test)
}
