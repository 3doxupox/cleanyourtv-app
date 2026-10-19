import java.time.Duration

plugins { id("cleanyourtv.android.lib") }

// Implementations of the core seams (W2, W5, W8 per PRODUCT-SPEC 6).
dependencies {
    implementation(project(":core:model"))
    implementation(project(":adb:wire"))
    implementation(project(":adb:pairing"))
    implementation(project(":core:trust"))
    implementation(project(":core:registry"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":testing:fake-adbd"))
    testImplementation(libs.kotlinx.coroutines.test)
}

// A hung test must never hold the shared forge build lock.
tasks.withType<Test>().configureEach { timeout.set(Duration.ofMinutes(5)) }
