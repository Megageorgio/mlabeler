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
// Only the dedicated Play workflow sets this; normal release artifacts retain their existing behaviour.
val playBuild = providers.gradleProperty("distribution").orNull == "play"
val webBuild = providers.gradleProperty("distribution").orNull == "web"
val playVersionCode = providers.gradleProperty("play.versionCode").orNull?.toIntOrNull()
if (playBuild) require(playVersionCode != null && playVersionCode > versionCodeOf(appVersion) && playVersionCode <= 2_100_000_000) {
    "A Play build needs -Pplay.versionCode=<unused code greater than ${versionCodeOf(appVersion)}>"
}

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
    inputs.property("playBuild", playBuild)
    inputs.property("webBuild", webBuild)
    outputs.dir(out)
    doLast {
        val f = out.get().file("mlabeler/app/AppInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package mlabeler.app\n\nobject AppInfo {\n    const val VERSION = \"$v\"\n    const val PLAY_STORE = $playBuild\n    const val WEB = $webBuild\n    const val PLUGINS_ENABLED = !$webBuild\n    const val TOOLKIT_ENABLED = ${!playBuild && !webBuild}\n    const val SELF_UPDATES_ENABLED = ${!playBuild && !webBuild}\n}\n")
    }
}

kotlin {
    wasmJs {
        outputModuleName.set("mlabeler")
        browser { commonWebpackConfig { outputFileName = "mlabeler.js" } }
        binaries.executable()
    }
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
            group("nativeScript") {
                withJvm()
                withAndroidTarget()
                group("ios")
            }
            group("skiko") {
                withJvm()
                group("ios")
                withWasmJs()
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
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val nativeScriptMain by getting { dependencies { implementation(libs.quickjs) } }
        val wasmJsMain by getting {
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3") }
        }
        val desktopMain by getting {
            // files that come with the program (the quick labelling model), also Android assets
            resources.srcDir("builtin")
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
        val androidUnitTest by getting { dependencies { implementation(kotlin("test-junit")) } }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test.ext:junit:1.2.1")
            }
        }
    }
}

android {
    namespace = "pw.msan.mlabeler"
    compileSdk = 36
    defaultConfig {
        applicationId = "pw.msan.mlabeler"
        minSdk = 26
        targetSdk = 36
        versionCode = if (playBuild) playVersionCode!! else versionCodeOf(appVersion)
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        keyAlias = System.getenv("MLABELER_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "mlabeler"
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
    sourceSets["main"].assets.srcDir("builtin")
    sourceSets["androidTest"].java.srcDir("src/androidInstrumentedTest/java")
    if (playBuild) sourceSets["main"].manifest.srcFile("src/androidPlay/AndroidManifest.xml")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

if (playBuild) {
    val validatePlayUploadKey = tasks.register("validatePlayUploadKey") {
        doLast {
            require(System.getenv("MLABELER_KEYSTORE")?.let { file(it).isFile } == true &&
                !System.getenv("MLABELER_KEYSTORE_PASSWORD").isNullOrBlank()) {
                "Play release bundles require the real upload key; debug-key fallback is not allowed"
            }
        }
    }
    tasks.matching { it.name == "validateSigningRelease" }.configureEach { dependsOn(validatePlayUploadKey) }
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
            modules("java.desktop", "jdk.unsupported", "jdk.charsets")
            windows { menu = true; perUserInstall = true; upgradeUuid = "6f1d1d7e-2b8e-4c39-9a41-6b2a0c6d0f2e"; iconFile.set(rootProject.file("art/icon.ico")) }
            macOS {
                bundleID = "pw.msan.mlabeler"
                minimumSystemVersion = "12.0"
                iconFile.set(rootProject.file("art/icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>mLabeler records samples for voicebanks and datasets.</string>
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>mLabeler connects to mVocalToolkit on your local network.</string>
                    """.trimIndent()
                }
            }
            linux { iconFile.set(rootProject.file("art/icon.png")) }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}

// State tests must never read or replace the real user's preferences.
tasks.withType<Test>().configureEach {
    environment("MLABELER_HOME", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
}

// Static deployment only. No release publication, server, account or upload key is needed.
val webShell by tasks.registering(Exec::class) {
    workingDir(rootProject.file("web"))
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm", "run", "build")
    inputs.files(rootProject.fileTree("web") { exclude("node_modules/**", "test-results/**", "playwright-report/**") })
    outputs.dir(layout.buildDirectory.dir("web-shell"))
}
val webDeployment by tasks.registering(Sync::class) {
    dependsOn("wasmJsBrowserDistribution", ":web-worker:wasmJsBrowserDistribution", webShell)
    doFirst { require(webBuild) { "Use -Pdistribution=web to build the web edition" } }
    into(layout.buildDirectory.dir("web-deploy"))
    from(layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) { exclude("**/*.map") }
    from(project(":web-worker").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) {
        into("inference"); exclude("**/*.map")
    }
    from(layout.buildDirectory.dir("web-shell"))
    from("builtin") { into(".") }
    from(rootProject.file("LICENSE"))
    from(rootProject.file("docs/WEB.md")) { rename { "DEPLOYMENT.md" } }
}
tasks.register<Zip>("webArchive") {
    dependsOn(webDeployment)
    from(webDeployment)
    destinationDirectory.set(layout.buildDirectory.dir("outputs/web"))
    archiveFileName.set("mLabeler-$appVersion-web.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Jars for a portable Windows build (JRE + launcher are added by tools/windows-portable.sh).
tasks.register<Copy>("windowsPortableLibs") {
    val jar = tasks.named("desktopJar")
    dependsOn(jar)
    from(jar)
    from(configurations.named("desktopRuntimeClasspath"))
    into(layout.buildDirectory.dir("windows-portable/app"))
}
