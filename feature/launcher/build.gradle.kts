import java.time.Duration

plugins {
    id("cleanyourtv.android.feature")
    // TODO(W7 merge): replace with id("cleanyourtv.android.roborazzi") (W7's convention plugin puts
    // Roborazzi on the build-logic classpath; a versioned request would then fail).
    alias(libs.plugins.roborazzi)
}

android {
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

roborazzi { outputDir.set(layout.projectDirectory.dir("src/test/screenshots")) }

dependencies {
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(project(":testing:fixtures"))
}

// Screenshot tests on the JVM: one fork, bounded heap, native graphics; a hang fails in 5 minutes.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(5))
    maxParallelForks = 1
    maxHeapSize = "1280m"
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
}
