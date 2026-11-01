import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.TreeMap

abstract class GenerateAtlasBuildProvenance : DefaultTask() {
    @get:Input abstract val variantName: Property<String>
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val testApplicationId: Property<String>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val compileSdk: Property<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val targetSdk: Property<Int>
    @get:Input abstract val scenarioId: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val repository = project.rootProject.projectDir.parentFile

        fun git(vararg arguments: String): String {
            val result = ProcessBuilder(listOf("git", *arguments))
                .directory(repository)
                .redirectErrorStream(true)
                .start()
            val output = result.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
            if (result.waitFor() != 0) {
                throw GradleException("Cannot resolve Git provenance metadata: git ${arguments.firstOrNull() ?: "command"} failed")
            }
            return output
        }

        val sourceSha = git("rev-parse", "HEAD")
        val sourceTreeSha = git("rev-parse", "HEAD^{tree}")
        val status = git("status", "--porcelain", "--untracked-files=all", "--", ".", ":!**/build/**")
        val scenario = scenarioId.get()
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}").matches(scenario)
            || Regex("(?i)(token|secret|serial|bearer)").containsMatchIn(scenario)
            || System.getenv().any { (name, value) ->
                Regex("(?i)(token|secret|serial|bearer)").containsMatchIn(name)
                    && value.isNotBlank() && (scenario == value || value.contains(scenario))
            }) {
            throw GradleException("atlasScenarioId must be a non-sensitive scenario label")
        }
        if (!Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(sourceSha)
            || !Regex("[0-9a-f]{40}|[0-9a-f]{64}").matches(sourceTreeSha)) {
            throw GradleException("Git returned an invalid source revision")
        }

        val manifest = TreeMap<String, Any>()
        manifest["application_id"] = applicationId.get()
        manifest["compile_sdk"] = compileSdk.get()
        manifest["min_sdk"] = minSdk.get()
        manifest["scenario_id"] = scenario
        manifest["source_sha"] = sourceSha
        manifest["source_tree_sha"] = sourceTreeSha
        manifest["target_sdk"] = targetSdk.get()
        manifest["test_application_id"] = testApplicationId.get()
        manifest["variant"] = variantName.get()
        manifest["version_code"] = versionCode.get()
        manifest["version_name"] = versionName.get()
        manifest["worktree_clean"] = status.isEmpty()

        fun jsonValue(value: Any): String = when (value) {
            is Boolean, is Number -> value.toString()
            else -> buildString {
                append('"')
                value.toString().forEach { character ->
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000c' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                    }
                }
                append('"')
            }
        }

        val json = manifest.entries.joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") {
            "  \"${it.key}\": ${jsonValue(it.value)}"
        }
        val assets = outputDirectory.get().asFile
        assets.mkdirs()
        File(assets, "atlas-build-provenance.json").writeText(json, StandardCharsets.UTF_8)
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val selectedQaTestBuildType = (project.findProperty("qaTestBuildType") as String?) ?: "debug"

android {
    namespace = "dev.atlas.coach"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "dev.atlas.coach"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    // Separate QA installation for physical-device qualification: it has its own
    // applicationId/data/Keystore namespace so the owner's ATLAS app, HOME role
    // and personal data are never replaced or read.
    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = (project.findProperty("qaSuffix") as String?) ?: ".qa"
            versionNameSuffix = "-qa"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        animationsDisabled = true
    }

    // Preserve the existing debug test target by default. The QA harness opts
    // into qa explicitly; a task alias below exposes that same target in the
    // required plain offline build command.
    testBuildType = selectedQaTestBuildType

    sourceSets {
        getByName("qa") {
            // Reuse the debug-only network security config; the two debug relay
            // helpers are copied into src/qa/java for the QA application variant.
            res.directories.add("src/debug/res")
        }
    }
}

val atlasScenarioId = providers.gradleProperty("atlasScenarioId")
    .orElse(providers.environmentVariable("ATLAS_SCENARIO_ID"))
    .orElse("unspecified")

androidComponents {
    onVariants { variant ->
        val output = variant.outputs.singleOrNull()
            ?: throw GradleException("ATLAS provenance requires a single APK output for ${variant.name}")
        val compileSdkValue = android.compileSdk?.let { api ->
            android.compileSdkMinor?.let { minor -> "$api.$minor" } ?: api.toString()
        } ?: android.compileSdkPreview
            ?: throw GradleException("ATLAS provenance cannot resolve compileSdk")
        val testApplicationIdProvider = variant.androidTest?.applicationId
            ?: variant.applicationId.map { "$it.test" }
        val provenanceTask = tasks.register<GenerateAtlasBuildProvenance>(
            "generate${variant.name.replaceFirstChar(Char::uppercase)}AtlasBuildProvenance",
        ) {
            variantName.set(variant.name)
            applicationId.set(variant.applicationId)
            this.testApplicationId.set(testApplicationIdProvider)
            versionCode.set(output.versionCode)
            versionName.set(output.versionName)
            compileSdk.set(compileSdkValue)
            minSdk.set(variant.minSdk.apiLevel)
            targetSdk.set(variant.targetSdk.apiLevel)
            scenarioId.set(atlasScenarioId)
            outputDirectory.set(layout.buildDirectory.dir("generated/atlasProvenance/${variant.name}/assets"))
            outputs.upToDateWhen { false }
        }

        val appAssets = variant.sources.assets
            ?: throw GradleException("ATLAS provenance requires an assets source for ${variant.name}")
        appAssets.addGeneratedSourceDirectory(provenanceTask, GenerateAtlasBuildProvenance::outputDirectory)
        variant.androidTest?.let { androidTest ->
            val testAssets = androidTest.sources.assets
                ?: throw GradleException("ATLAS provenance requires AndroidTest assets for ${variant.name}")
            testAssets.addGeneratedSourceDirectory(provenanceTask, GenerateAtlasBuildProvenance::outputDirectory)
        }
    }
}

if (selectedQaTestBuildType != "qa") {
    tasks.register<org.gradle.api.tasks.Exec>("assembleQaAndroidTest") {
        group = "build"
        description = "Builds the isolated QA AndroidTest APK using the qa test build type."
        dependsOn("assembleQa")
        val suffix = (project.findProperty("qaSuffix") as String?) ?: ".qa"
        val scenario = atlasScenarioId.get()
        commandLine(
            project.rootProject.projectDir.resolve("gradlew").absolutePath,
            "-PqaTestBuildType=qa",
            "-PqaSuffix=$suffix",
            "-PatlasScenarioId=$scenario",
            ":app:assembleQaAndroidTest",
            "--offline",
        )
        workingDir(project.rootProject.projectDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Pinned maintained WebSocket client; supports the app's minSdk 26.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Bouncy Castle Licence; 1.86 exposes supported lightweight X25519 and
    // explicit-nonce ChaCha20-Poly1305 APIs; Tink's corresponding X25519 API is
    // @Alpha and its nonce-taking AEAD lives in an internal package. BC uses
    // Java 8 APIs and works with minSdk 26; verified against the published API.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.work:work-testing:2.10.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
