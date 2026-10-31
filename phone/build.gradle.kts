plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    // Personal build: the release-time lintVital pass adds minutes and catches nothing the tests do not.
    lint { checkReleaseBuilds = false }
    namespace = "dev.kyungmin.kcoach.phone"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "dev.kyungmin.kcoach"
        minSdk = 29
        targetSdk = 35
        versionCode = 61
        versionName = "0.2.22"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/kmnoh12/watchgym/releases/latest/download/update-phone.json\"",
        )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    androidResources { noCompress += "task" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Keep Compose tests in one graphics backend; mixing legacy/native sandboxes can stall idling.
        unitTests.all { it.systemProperty("robolectric.graphicsMode", "NATIVE") }
    }
    buildTypes {
        // Shrunk build for in-app updates: same debug key, so it installs over debug builds.
        create("ota") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":updates"))
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    constraints {
        implementation("androidx.fragment:fragment:1.3.6") {
            because("CameraX brings Fragment 1.1.0; ActivityResult camera permissions require Fragment 1.3.0 or newer")
        }
    }
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.0")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Raw ActivityController tests create real Compose views outside a Compose rule. Running
// those before Compose-rule tests in one Robolectric JVM poisons Compose idling (the
// ActivityController + Compose-rule pair reproduces it). Keep both suites in
// the full gate, but give the Compose rule its own JVM instead of skipping screen tests.
afterEvaluate {
    val unit = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").get()
    val setup = unit.taskDependencies.getDependencies(unit).toList()
    val composeTests = tasks.register<org.gradle.api.tasks.testing.Test>("testDebugCompose") {
        description = "Runs phone Compose/Roborazzi tests in a fresh Robolectric process"
        group = "verification"
        testClassesDirs = unit.testClassesDirs
        classpath = unit.classpath
        systemProperties(unit.systemProperties)
        listOf("record", "compare", "verify").forEach { mode ->
            systemProperty("roborazzi.test.$mode", providers.gradleProperty("roborazzi.test.$mode").getOrElse("false"))
        }
        jvmArgs(unit.jvmArgs.orEmpty())
        jvmArgumentProviders.addAll(unit.jvmArgumentProviders)
        maxHeapSize = unit.maxHeapSize
        workingDir = unit.workingDir
        include("**/ui/**", "**/CalibrationCameraActivityTest*")
        dependsOn(setup)
    }
    unit.exclude("**/ui/**", "**/CalibrationCameraActivityTest*")
}
