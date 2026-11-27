plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ivo.droiddrop"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.ivo.droiddrop"
        minSdk = 35
        targetSdk = 37
        // Release builds pass these from the tag (scripts/release-version.sh).
        versionCode = providers.gradleProperty("droiddrop.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("droiddrop.versionName").getOrElse("0.1.0")
    }

    // The release key lives outside the repo; without it assembleRelease leaves an unsigned APK.
    val releaseKeystore = providers.environmentVariable("DROIDDROP_KEYSTORE").orNull
    if (releaseKeystore != null) {
        val releaseSigning = signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("DROIDDROP_KEYSTORE_PASSWORD").get()
            keyAlias = "droiddrop"
            keyPassword = storePassword
        }
        buildTypes.getByName("release").signingConfig = releaseSigning
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// AGP 9 registers generate*ComposePreviewRunfiles for every variant and refuses to
// configure it unless that variant has a unit-test component with Android resources.
// VariantBuilder.enableUnitTest is hidden; the live property is on HasUnitTestBuilder.
androidComponents {
    beforeVariants { builder ->
        (builder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.code.scanner)
    implementation(libs.androidx.fragment)
    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
