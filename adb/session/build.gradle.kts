plugins { id("cleanyourtv.jvm") }

// Implements the core.session interfaces of :core:model over :adb:wire (W2).
dependencies {
    api(project(":core:model"))
    implementation(project(":adb:wire"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":testing:fake-adbd"))
    testImplementation(project(":testing:fixtures"))
    testImplementation(libs.kotlinx.coroutines.test)
}
