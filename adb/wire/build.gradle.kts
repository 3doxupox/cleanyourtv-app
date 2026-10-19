plugins { id("cleanyourtv.jvm") }

// Endpoint, Sha256 and ShellLine appear in the 5.3 signatures.
dependencies {
    api(project(":core:model"))
}
