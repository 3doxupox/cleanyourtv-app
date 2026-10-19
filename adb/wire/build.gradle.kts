import java.time.Duration

plugins { id("cleanyourtv.jvm") }

// Endpoint, Sha256 and ShellLine appear in the 5.3 signatures. Vendored from dadb 2.0.0 (NOTICE);
// no dadb artifact (D11).
dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":testing:fake-adbd"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotest.property)
}

// The hostile-frame property test (HostileFrameFuzzTest) runs for cleanyourtv.fuzzSeconds
// (default 15); the 10-minute CI run passes -Pcleanyourtv.fuzzSeconds=600.
val fuzzSeconds = providers.gradleProperty("cleanyourtv.fuzzSeconds").orElse("15")
val fuzzSeed = providers.gradleProperty("cleanyourtv.fuzzSeed").orElse("")
tasks.withType<Test>().configureEach {
    systemProperty("cleanyourtv.fuzzSeconds", fuzzSeconds.get())
    if (fuzzSeed.get().isNotEmpty()) systemProperty("cleanyourtv.fuzzSeed", fuzzSeed.get())
    // A hung test must never hold the shared forge build lock: 5 minutes, plus the fuzz budget
    // when a longer fuzz run is asked for.
    val extra = (fuzzSeconds.get().toLongOrNull() ?: 15L).let { if (it > 15L) 2 * it else 0L }
    timeout.set(Duration.ofMinutes(5).plusSeconds(extra))
}
