plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:model"))
    testImplementation(project(":testing:fixtures"))
}
