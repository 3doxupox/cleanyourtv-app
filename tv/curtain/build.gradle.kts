// The TV curtain (RFC-CURTAIN-1, app-design/CURTAIN-PLAN.md): what the TV shows while the phone runs
// Setup. Built, signed and copied into :android:platform's assets by scripts/build-curtain-on-forge.sh.
plugins {
    id("cleanyourtv.tv.app")
}

android {
    namespace = "tv.cleanyour.curtain"
    defaultConfig {
        applicationId = "tv.cleanyour.curtain"
        versionCode = 1
        versionName = "0.1"
    }
}
