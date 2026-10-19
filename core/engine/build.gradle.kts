plugins { id("cleanyourtv.jvm") }

// Plan, Step and GuardedSection come from :core:planner, so the engine sits one layer above it
// (RFC-W1-1 in DECISIONS-W1.md).
dependencies {
    api(project(":core:planner"))
    api(project(":core:probe"))
    api(project(":core:ops"))
}
