plugins {
    `kotlin-dsl`
}

group = "tv.cleanyour.buildlogic"

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.serialization.gradlePlugin)
    testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

gradlePlugin {
    plugins {
        register("root") {
            id = "cleanyourtv.root"
            implementationClass = "RootConventionPlugin"
        }
        register("jvm") {
            id = "cleanyourtv.jvm"
            implementationClass = "JvmConventionPlugin"
        }
        register("serialization") {
            id = "cleanyourtv.serialization"
            implementationClass = "SerializationConventionPlugin"
        }
        register("androidLib") {
            id = "cleanyourtv.android.lib"
            implementationClass = "AndroidLibConventionPlugin"
        }
        register("androidCompose") {
            id = "cleanyourtv.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "cleanyourtv.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidApp") {
            id = "cleanyourtv.android.app"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("androidBenchmark") {
            id = "cleanyourtv.android.benchmark"
            implementationClass = "AndroidBenchmarkConventionPlugin"
        }
    }
}
