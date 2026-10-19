plugins { id("cleanyourtv.jvm") }

dependencies {
    api(project(":core:engine"))
    api(project(":core:registry"))
    api(project(":core:backup"))
}
