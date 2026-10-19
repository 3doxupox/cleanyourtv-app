plugins { id("cleanyourtv.jvm") }

// W9 owns: JVM harness against TV emulators (ARCHITECTURE 6.6).
dependencies {
    implementation(project(":adb:session"))
    implementation(project(":core:engine"))
    implementation(project(":core:probe"))
    implementation(project(":testing:fixtures"))
}
