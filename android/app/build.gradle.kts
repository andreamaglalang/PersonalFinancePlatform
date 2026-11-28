import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

abstract class PrepareLocalDebugTrust : DefaultTask() {
    @get:InputFile @get:Optional abstract val certificateFile: RegularFileProperty
    @get:InputFile abstract val defaultPolicy: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val output = outputDirectory.get().asFile
        val source = certificateFile.orNull?.asFile
        if (source == null) {
            output.deleteRecursively()
            output.mkdirs()
            return
        }
        val pem = source.readText()
        require(Regex("\\s*-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----\\s*").matches(pem)) {
            "Local Android trust requires one public PEM certificate, never a private key or bundle."
        }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate
        certificate.checkValidity()
        require(certificate.basicConstraints < 0
            && certificate.subjectAlternativeNames?.any { it[0] == 2 && it[1] == "localhost" } == true
            && certificate.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") == true) {
            "Local Android trust requires a valid localhost server leaf certificate, not a CA."
        }
        val policy = defaultPolicy.get().asFile.readText()
        val userAnchor = "<certificates src=\"user\" />"
        check(policy.split(userAnchor).size == 2 && policy.split("<domain-config").size == 2
            && policy.contains("<domain includeSubdomains=\"false\">localhost</domain>")) {
            "Review the localhost-only debug trust policy before generating local trust."
        }
        output.deleteRecursively()
        output.resolve("raw/owli_localhost_dev_certificate.pem").apply {
            parentFile.mkdirs()
            writeText(pem)
        }
        output.resolve("xml/owli_debug_local_network_security_config.xml").apply {
            parentFile.mkdirs()
            writeText(policy.replace(userAnchor,
                "$userAnchor\n            <certificates src=\"@raw/owli_localhost_dev_certificate\" />"))
        }
    }
}

// Optional, per-checkout public certificate. No device-wide CA installation or TLS override.
val localDevCertificate = layout.projectDirectory.file("../.local/localhost.pem")
val localDebugTrust = tasks.register<PrepareLocalDebugTrust>("prepareLocalDebugTrust") {
    if (localDevCertificate.asFile.isFile) certificateFile.set(localDevCertificate)
    defaultPolicy.set(layout.projectDirectory.file("src/debug/res/xml/owli_debug_network_security_config.xml"))
    outputDirectory.set(layout.buildDirectory.dir("generated/owliLocalDebugTrust"))
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
        // Release versioning (handoff guide §11): -PowliVersionCode=<n> -PowliVersionName=<x.y.z>. The deployment team must
        // raise versionCode for every published update, or Android refuses the in-place upgrade.
        versionCode = (project.findProperty("owliVersionCode") as String?)?.trim()?.let { raw ->
            requireNotNull(raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }) { "owliVersionCode must be a whole number from 1 to 2100000000." }
        } ?: 1
        versionName = (project.findProperty("owliVersionName") as String?)?.trim()?.also { name ->
            require(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$").matches(name)) { "owliVersionName must look like 1.0.0." }
        } ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OWLI_API_BASE_URL", "\"\"")
    }

    buildTypes {
        debug {
            manifestPlaceholders["owliDebugNetworkSecurityConfig"] = if (localDevCertificate.asFile.isFile)
                "@xml/owli_debug_local_network_security_config" else "@xml/owli_debug_network_security_config"
        }
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
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(localDebugTrust, PrepareLocalDebugTrust::outputDirectory)
    }
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
