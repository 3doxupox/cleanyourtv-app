import java.time.Duration

plugins { id("cleanyourtv.jvm") }

// W2 owns: in-process adbd with personas and fault knobs (ARCHITECTURE 6.4).
dependencies {
    api(project(":adb:wire"))
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// A hung test must never hold the shared forge build lock.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(5)) }
