plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:spec"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.apksig)
}

// Build-time assertion (PRODUCT-SPEC W5, 7.3 item 4): the bundled launcher APK's sha256, size and
// signer digest must equal PinnedLauncher. Runs with `check` and before :android:platform builds.
val launcherApk = rootProject.layout.projectDirectory.file("android/platform/src/main/assets/bundled/cleanyourtv-launcher.apk")
val verifyLauncherPin by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when the bundled launcher APK does not match PinnedLauncher."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("tv.cleanyour.core.trust.LauncherPinCheckKt")
    args(launcherApk.asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    inputs.file(launcherApk)
    val stamp = layout.buildDirectory.file("launcher-pin/ok")
    outputs.file(stamp)
    doLast { stamp.get().asFile.writeText("ok\n") }
}
tasks.named("check") { dependsOn(verifyLauncherPin) }
