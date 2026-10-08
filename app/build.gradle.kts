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

/** Android version code: 0.2.1-beta3 → 200143; a stable release ends in 99, after its alphas (01–39) and betas (40–98). */
fun versionCodeOf(v: String): Int {
    val m = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-(alpha|beta)(\\d+)?)?").find(v) ?: return 1
    val (ma, mi, pa, kind, n) = m.destructured
    val tail = when (kind) { "alpha" -> (n.ifEmpty { "1" }.toInt()).coerceIn(1, 39); "beta" -> 40 + (n.ifEmpty { "1" }.toInt()).coerceIn(0, 58); else -> 99 }
    return ma.toInt() * 10_000_000 + mi.toInt() * 100_000 + pa.ifEmpty { "0" }.toInt() * 100 + tail
}

// the version shown in the program (Settings → About)
val appInfoDir = layout.buildDirectory.dir("generated/appinfo")
val generateAppInfo by tasks.registering {
    val out = appInfoDir
    val v = appVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val f = out.get().file("mlabeler/app/AppInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package mlabeler.app\n\nobject AppInfo {\n    const val VERSION = \"$v\"\n}\n")
    }
}

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
        commonMain {
            kotlin.srcDir(generateAppInfo)
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
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // a fixed key makes each new APK install over the previous one: given by the build machine (CI secrets),
    // otherwise the debug key of that machine is used
    val keystore = System.getenv("MLABELER_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    if (keystore != null) signingConfigs.create("release") {
        storeFile = keystore
        storePassword = System.getenv("MLABELER_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("MLABELER_KEY_ALIAS") ?: "mlabeler"
        keyPassword = System.getenv("MLABELER_KEY_PASSWORD") ?: System.getenv("MLABELER_KEYSTORE_PASSWORD")
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

compose.desktop {
    application {
        mainClass = "mlabeler.app.MainKt"
        // sun.jnu.encoding is left to the system: forcing UTF-8 garbles a non-Latin Windows user name in user.home
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
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
