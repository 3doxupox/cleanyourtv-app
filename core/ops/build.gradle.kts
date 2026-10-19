import java.time.Duration

plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:model"))
    testImplementation(project(":testing:fixtures"))
}

// A hung coroutine or a deadlocked run must fail the build, not stall it.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(5)) }
