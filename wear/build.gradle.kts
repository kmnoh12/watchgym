plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    // Personal build: the release-time lintVital pass adds minutes and catches nothing the tests do not.
    lint { checkReleaseBuilds = false }
    namespace = "dev.kyungmin.kcoach.wear"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes {
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
    defaultConfig {
        applicationId = "dev.kyungmin.kcoach"
        minSdk = 30
        targetSdk = 35
        versionCode = 51
        versionName = "0.2.15"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/kmnoh12/watchgym/releases/latest/download/update-watch.json\"",
        )
    }
}

dependencies {
    implementation(project(":updates"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    // Health Services already brings this at runtime; expose its Future API when compiling remote launch calls.
    implementation("com.google.guava:guava:32.0.1-android")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
}
