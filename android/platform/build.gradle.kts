plugins { id("cleanyourtv.android.lib") }

// Implementations of the core seams (W2, W5, W8 per PRODUCT-SPEC 6).
dependencies {
    implementation(project(":core:model"))
    implementation(project(":adb:wire"))
    implementation(project(":adb:pairing"))
    implementation(project(":core:trust"))
    implementation(project(":core:registry"))
}
