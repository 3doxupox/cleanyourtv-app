plugins {
    id("cleanyourtv.jvm")
    id("cleanyourtv.serialization")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.serialization.json)
}
