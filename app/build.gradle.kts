import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.android.application)
}

val appVersion = project.property("app.version") as String

kotlin {
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "MLabeler"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate {
        common {
            group("skiko") {
                withJvm()
                group("ios")
            }
            group("jvmShared") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
            languageSettings.optIn("androidx.compose.foundation.ExperimentalFoundationApi")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
        }
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.quickjs)
        }
        val desktopMain by getting {
            dependencies {
                // -Pdesktop.target=windows builds a Windows portable folder from any OS (see windowsPortableLibs)
                val windowsOnly = findProperty("desktop.target") == "windows"
                if (windowsOnly) implementation(compose.desktop.windows_x64) else implementation(compose.desktop.currentOs)
                // system folder dialog (Explorer with the address bar on Windows)
                val lwjgl = "3.3.6"
                implementation("org.lwjgl:lwjgl:$lwjgl")
                implementation("org.lwjgl:lwjgl-nfd:$lwjgl")
                val natives = if (windowsOnly) listOf("natives-windows")
                    else listOf("natives-windows", "natives-linux", "natives-macos", "natives-macos-arm64")
                for (n in natives) {
                    runtimeOnly("org.lwjgl:lwjgl:$lwjgl:$n")
                    runtimeOnly("org.lwjgl:lwjgl-nfd:$lwjgl:$n")
                }
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "io.github.megageorgio.mlabeler"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.megageorgio.mlabeler"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = appVersion
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

compose.desktop {
    application {
        mainClass = "mlabeler.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "mLabeler"
            packageVersion = appVersion.substringBefore('-').let { if (it.startsWith("0.")) "1." + it.removePrefix("0.") else it }
            modules("java.desktop", "jdk.unsupported")
            windows { menu = true; perUserInstall = true; upgradeUuid = "6f1d1d7e-2b8e-4c39-9a41-6b2a0c6d0f2e"; iconFile.set(rootProject.file("art/icon.ico")) }
            macOS { bundleID = "io.github.megageorgio.mlabeler"; iconFile.set(rootProject.file("art/icon.icns")) }
            linux { iconFile.set(rootProject.file("art/icon.png")) }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// Jars for a portable Windows build (JRE + launcher are added by tools/windows-portable.sh).
tasks.register<Copy>("windowsPortableLibs") {
    val jar = tasks.named("desktopJar")
    dependsOn(jar)
    from(jar)
    from(configurations.named("desktopRuntimeClasspath"))
    into(layout.buildDirectory.dir("windows-portable/app"))
}
