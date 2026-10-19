plugins { id("cleanyourtv.jvm") }

// W2 owns: in-process adbd with personas and fault knobs (ARCHITECTURE 6.4).
dependencies {
    api(project(":adb:wire"))
    api(project(":core:model"))
}
