import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.elect3.mobdev.personalfinance"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.elect3.mobdev.personalfinance"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OWLI_API_BASE_URL", "\"\"")
    }

    buildTypes {
        release {
            // Deployment endpoint (R11/D10): -PowliApiBaseUrl=https://host/ or ORG_GRADLE_PROJECT_owliApiBaseUrl.
            // No default host: without it the release app stays explicitly unconfigured.
            val endpoint = (project.findProperty("owliApiBaseUrl") as String?)?.trim().orEmpty()
            require(endpoint.isEmpty() || Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/([A-Za-z0-9._~-]+/)*$").matches(endpoint)) {
                "owliApiBaseUrl must be an https:// origin with an optional path ending in '/', without credentials, query or fragment."
            }
            buildConfigField("String", "OWLI_API_BASE_URL", "\"$endpoint\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Run the no-logging contract against the actual release implementation on the JVM.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        (variant as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
    // Room: offline-first local transaction history and pending-sync queue.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation("com.google.code.gson:gson:2.11.0") {
        // Compile-time tooling annotations only; not needed by Gson at Android runtime.
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
}
