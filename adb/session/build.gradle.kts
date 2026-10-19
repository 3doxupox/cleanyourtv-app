plugins { id("cleanyourtv.jvm") }

// Implements the core.session interfaces of :core:model over :adb:wire (W2).
dependencies {
    api(project(":core:model"))
    implementation(project(":adb:wire"))
}
