import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuildConfigField
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.wutz.apollo"
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/src/test/resources/tls")

    lint {
        disable += "MissingTranslation"
    }
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.wutz.apollo"
        minSdk = 26
        targetSdk = 37
        // Version overrides passed from CI via -PversionName / -PversionCode
        // Falls back to defaults for local development.
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as? String) ?: "1.0-dev"
        // GIT_SHA is added lazily per variant in androidComponents below.
    }

    // All four variables are needed to sign release builds (see BUILD.md).
    val releaseSigningEnvPresent =
        listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
            .all { System.getenv(it) != null }

    signingConfigs {
        getByName("debug") {
            // Stable debug keystore committed to the repo so debug APKs keep
            // the same signature across machines and CI runs. CI runners use
            // an ephemeral default debug keystore otherwise, which makes every
            // CI debug APK uninstallable over a previously installed build.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val isReleaseBuild =
                gradle.startParameter.taskNames.any { name ->
                    name.contains("Release", ignoreCase = true) ||
                        name.contains("Play") ||
                        name == "build"
                }

            if (isReleaseBuild) {
                val storePath = System.getenv("KEYSTORE_PATH")
                val storePass = System.getenv("KEYSTORE_PASSWORD")
                val alias = System.getenv("KEY_ALIAS")
                val keyPass = System.getenv("KEY_PASSWORD")

                if (storePath != null && storePass != null && alias != null && keyPass != null) {
                    storeFile = file(storePath)
                    storePassword = storePass
                    keyAlias = alias
                    keyPassword = keyPass
                } else {
                    // Env vars missing — signing deferred to release workflow.
                    // Don't hard-require(): that would break compileReleaseKotlin
                    // in CI where keystore secrets aren't set.
                    logger.warn("Release signing config missing env vars — signing deferred to release workflow")
                    storeFile = file("dummy.keystore")
                    storePassword = "dummy"
                    keyAlias = "dummy"
                    keyPassword = "dummy"
                }
            } else {
                // Dummy values for evaluation configuration during non-release builds
                storeFile = file("dummy.keystore")
                storePassword = "dummy"
                keyAlias = "dummy"
                keyPassword = "dummy"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"

            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs["release"]
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            // GitHub distribution: the in-app updater checks GitHub releases
            // and installs the APK (needs REQUEST_INSTALL_PACKAGES).
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        }

        // Google Play distribution (issue #7): the release build without the
        // GitHub self-updater. src/play/AndroidManifest.xml removes
        // REQUEST_INSTALL_PACKAGES; checkPlayPermissions guards that.
        // `./gradlew bundlePlay` → app/build/outputs/bundle/play/app-play.aab
        // A build type (not a product flavor) keeps every existing task name
        // and output path (assembleDebug, assembleRelease, …) unchanged.
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
            // Signed with the release key (the Play upload key) when the
            // signing variables are set; otherwise an unsigned bundle.
            signingConfig = if (releaseSigningEnvPresent) signingConfigs["release"] else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    dependenciesInfo {
        includeInApk = false
    }

    packaging {
        resources {
            pickFirsts += "META-INF/AL2.0"
            pickFirsts += "META-INF/LGPL2.1"
            pickFirsts += "META-INF/LICENSE"
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/LICENSE-notice.md"
            pickFirsts += "META-INF/NOTICE"
            pickFirsts += "META-INF/NOTICE.md"
        }
    }
}

// Overwrite version-control-info with empty content for reproducible builds
// F-Droid CI uses detached HEAD (branches: []) while GitHub CI uses main
// (branches: ["main"]) — this field in the generated textproto causes a
// byte-level APK difference. Emptying the file makes both CI environments
// produce identical artifacts.
tasks.matching { it.name.endsWith("VersionControlInfo") }.configureEach {
    doLast {
        outputs.files.filter { it.exists() }.forEach { it.writeText("") }
    }
}

// Use a fixed prefix: git --short varies with the checkout's object graph
// (shallow GitHub Actions vs. full F-Droid clone), breaking reproducible APKs.
// Lazy, so the commit is not a configuration-cache input, and empty outside a
// git checkout (source tarball); the About screen hides a blank SHA.
val gitShaProvider =
    providers
        .exec {
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().take(7) }

androidComponents {
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "GIT_SHA",
            gitShaProvider.map { BuildConfigField("String", "\"$it\"", null) },
        )
    }

    // Fails the Play build if the merged manifest still requests the permission
    // the GitHub self-updater needs (issue #7). bundlePlay/assemblePlay depend on it.
    onVariants(selector().withBuildType("play")) { variant ->
        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val check =
            tasks.register("checkPlayPermissions") {
                group = "verification"
                description = "Fails if the Play build's merged manifest requests REQUEST_INSTALL_PACKAGES."
                inputs.file(mergedManifest)
                doLast {
                    val manifest = mergedManifest.get().asFile
                    if (manifest.readText().contains("android.permission.REQUEST_INSTALL_PACKAGES")) {
                        throw GradleException("Play manifest must not request REQUEST_INSTALL_PACKAGES: $manifest")
                    }
                    logger.lifecycle("checkPlayPermissions: REQUEST_INSTALL_PACKAGES absent.")
                }
            }
        tasks.matching { it.name == "bundlePlay" || it.name == "assemblePlay" }.configureEach {
            dependsOn(check)
        }
    }
}

kotlin {
    jvmToolchain(21)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.leakcanary.android)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // Native Compose LaTeX rendering
    implementation(libs.latex.base)
    implementation(libs.latex.parser)
    implementation(libs.latex.renderer)

    // Image loading (Coil 3)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    // Media3 (ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    // Encrypted storage
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore)
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Local database (Room 3) — message persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.sqlcipher)
    ksp(libs.androidx.room.compiler)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.okhttp.mockwebserver)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

// ── ktlint (pinned, resolved by Gradle) ────────────────────────────────────
//
// Runs ktlint-cli from Maven Central instead of whatever `ktlint` is on PATH
// (approach from the ktlint docs, no plugin). Sources and .editorconfig are
// inputs and the checkstyle report is the output, so an unchanged tree is
// up to date.
val ktlint: Configuration = configurations.create("ktlint")

dependencies {
    ktlint(libs.ktlint.cli) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
}

val ktlintSources =
    fileTree("src") {
        include("**/*.kt")
    }
val ktlintEditorConfig = rootProject.layout.projectDirectory.file(".editorconfig")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Runs ktlint check."
    val report = layout.buildDirectory.file("reports/ktlint/ktlint-check.xml")
    inputs.files(ktlintSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(ktlintEditorConfig).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(report)
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootProject.projectDir
    args(
        "--reporter=plain",
        "--reporter=checkstyle,output=${report.get().asFile.absolutePath}",
        "app/src/**/*.kt",
    )
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Runs ktlint format."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootProject.projectDir
    // See https://github.com/pinterest/ktlint/issues/1195
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    args("-F", "app/src/**/*.kt")
}

// ── Hardcoded Color Guard (issue #622) ────────────────────────────────────
//
// Fails the build if a hardcoded Color literal (Color(0x...) or named
// Color.White/Black/Red/Green/Gray/etc.) is introduced outside the theme
// module, *Preview.kt files, and _test sources. Pairing/Auth screens are
// exempt via the per-path overlay list below.
//
// Acceptable replacements (see docs/color-hardcoded-audit.md):
//   - MaterialTheme.colorScheme.<token>
//   - LocalHermesStatusColors.current.<semantic>
//   - Color.Transparent / Color.Unspecified (intentional)

// Resolve paths at configuration time (config-cache compatible).
val colorGuardSrcDir = layout.projectDirectory
    .dir("src/main/java/io/wutz/apollo")
val colorGuardExemptions = listOf(
    "/theme/",
    "PairingScreen.kt",
    "AuthLoginScreen.kt",
    "Preview.kt",
)
val colorGuardHexPattern = Regex("""Color\(\s*0x[0-9A-Fa-f]{6,8}\s*\)""")
val colorGuardNamedPattern = Regex("""Color\.(White|Black|Red|Green|Gray|LightGray|DarkGray|Yellow|Blue|Cyan|Magenta)\b""")

tasks.register("checkColorLiterals") {
    group = "verification"
    description = "Fails if hardcoded Color(...) literals appear outside theme/, *Preview.kt, and _test."

    val srcDir = colorGuardSrcDir
    val exemptions = colorGuardExemptions
    val hexPattern = colorGuardHexPattern
    val namedPattern = colorGuardNamedPattern

    doLast {
        val offenders = mutableListOf<Pair<String, Int>>()
        srcDir.asFile.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            // Issue #622: normalize Windows separators before checking path exemptions.
            if (exemptions.any { file.invariantSeparatorsPath.contains(it) }) return@forEach
            file.useLines { lines ->
                lines.forEachIndexed { idx, raw ->
                    val line = raw.trim()
                    if (line.startsWith("import ")) return@forEachIndexed
                    if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                    if (hexPattern.containsMatchIn(line) || namedPattern.containsMatchIn(line)) {
                        offenders.add(file.absolutePath to (idx + 1))
                    }
                }
            }
        }

        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (path, line) ->
                "  - $path:$line"
            }
            throw GradleException(
                "Hardcoded Color literals found outside theme/ + *Preview.kt + _test:\n$report\n\n" +
                    "Replace with MaterialTheme.colorScheme.<token>, " +
                    "LocalHermesStatusColors.current.<semantic>, or " +
                    "Color.Transparent / Color.Unspecified (intentional).\n" +
                    "See docs/color-hardcoded-audit.md.",
            )
        }
        logger.lifecycle("checkColorLiterals: no hardcoded Color literals found. ✅")
    }
}

// ── German string parity (EN+DE rule, see AGENTS.md) ───────────────────────
//
// Every translatable string, plurals and string-array in values/ must also
// exist in values-de/. Lint's MissingTranslation is disabled because the
// other locales are unmaintained, so this guards German only.
val germanStringsBaseDir = layout.projectDirectory.dir("src/main/res/values")
val germanStringsDeDir = layout.projectDirectory.dir("src/main/res/values-de")

tasks.register("checkGermanStrings") {
    group = "verification"
    description = "Fails if a translatable string in values/ is missing from values-de/."

    val baseDir = germanStringsBaseDir
    val deDir = germanStringsDeDir
    val marker = layout.buildDirectory.file("reports/checkGermanStrings.txt")
    inputs.files(fileTree(baseDir) { include("*.xml") }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(deDir) { include("*.xml") }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(marker)

    doLast {
        val kinds = setOf("string", "plurals", "string-array")

        fun keys(dir: File): Map<String, String> {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val result = sortedMapOf<String, String>()
            dir
                .listFiles { f -> f.isFile && f.extension == "xml" }
                .orEmpty()
                .sortedBy { it.name }
                .forEach { file ->
                    val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
                    for (i in 0 until children.length) {
                        val node = children.item(i) as? org.w3c.dom.Element ?: continue
                        if (node.tagName !in kinds) continue
                        if (node.getAttribute("translatable") == "false") continue
                        result["${node.tagName}/${node.getAttribute("name")}"] = file.name
                    }
                }
            return result
        }

        val base = keys(baseDir.asFile)
        val german = keys(deDir.asFile)
        val missing = base.filterKeys { it !in german }
        if (missing.isNotEmpty()) {
            val report = missing.entries.joinToString("\n") { (key, file) -> "  - $key (values/$file)" }
            throw GradleException(
                "${missing.size} translatable resource(s) in values/ are missing from values-de/:\n$report\n\n" +
                    "Add the German text to values-de/ (or mark a non-translatable value translatable=\"false\").",
            )
        }
        val summary = "checkGermanStrings: all ${base.size} translatable resources exist in values-de/."
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText(summary + "\n")
        }
        logger.lifecycle(summary)
    }
}

tasks.named("check") {
    dependsOn("checkColorLiterals", "checkGermanStrings")
}

// The local gate: everything CI checks except the emulator tests.
tasks.register("gate") {
    group = "verification"
    description = "Runs the local pre-commit gate (style, guards, unit tests, lint, debug/release/androidTest builds)."
    dependsOn(
        "ktlintCheck",
        "checkColorLiterals",
        "checkGermanStrings",
        "testDebugUnitTest",
        "lintDebug",
        "assembleDebug",
        "compileDebugAndroidTestKotlin",
        "compileReleaseKotlin",
        "checkPlayPermissions",
    )
}

val includeBenchmarks = providers.gradleProperty("includeBenchmarks").isPresent
val testForks =
    providers
        .gradleProperty("testForks")
        .orNull
        ?.toIntOrNull()
        ?.coerceAtLeast(1) ?: 2

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Two forks protect a developer machine's RAM; CI passes -PtestForks=4.
    maxParallelForks = testForks
    // Assertion-free timing probes are opt-in: ./gradlew testDebugUnitTest -PincludeBenchmarks
    if (!includeBenchmarks) {
        filter { excludeTestsMatching("*Benchmark") }
    }
}
