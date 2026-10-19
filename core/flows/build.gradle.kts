import java.time.Duration

plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:engine"))
    api(project(":core:registry"))
    api(project(":core:backup"))

    testImplementation(project(":testing:fixtures"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

// A hung coroutine test must fail fast instead of holding forge's shared build lock.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(3))
    testLogging { events("started", "passed", "failed", "skipped") }
}
