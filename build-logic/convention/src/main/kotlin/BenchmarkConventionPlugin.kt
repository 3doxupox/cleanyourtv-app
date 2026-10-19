import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import tv.cleanyour.buildlogic.Toolchain

// :benchmark (W9): a com.android.test module that drives the app's `benchmark` build type with
// Macrobenchmark (startup budgets of ARCHITECTURE 4, trend only in 0.1) and generates the Baseline
// Profile that release builds use from app/src/main/baseline-prof.txt. Only the `benchmark`
// variant exists, so `assembleDebug` and `check` do not build a test APK nobody runs.
class AndroidBenchmarkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        extensions.configure<TestExtension> {
            namespace = "tv.cleanyour.benchmark"
            compileSdk = Toolchain.COMPILE_SDK
            targetProjectPath = ":app"
            defaultConfig {
                minSdk = Toolchain.MIN_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            buildTypes {
                create(BENCHMARK) {
                    isDebuggable = true
                    signingConfig = signingConfigs.getByName("debug")
                    matchingFallbacks += listOf("release")
                }
            }
            compileOptions {
                sourceCompatibility = Toolchain.JAVA
                targetCompatibility = Toolchain.JAVA
            }
            experimentalProperties["android.experimental.self-instrumenting"] = true
        }
        extensions.configure<TestAndroidComponentsExtension> {
            beforeVariants { variant -> variant.enable = variant.buildType == BENCHMARK }
        }
    }

    companion object {
        const val BENCHMARK = "benchmark"
    }
}
