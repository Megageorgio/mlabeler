plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    wasmJs {
        outputModuleName.set("inference")
        browser { commonWebpackConfig { outputFileName = "inference.js" } }
        binaries.executable()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
