plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:spec"))
    api(project(":core:ops"))
    // JSON element API for manifest.json, changes.json and restore-point.json (no compiler plugin)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":testing:fixtures"))
}
