import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("dev.ivo.droiddrop.devcli.MainKt")
    applicationName = "devcli"
}

dependencies {
    implementation(project(":core"))
    implementation(testFixtures(project(":core")))
    // SelfSigned is a test fixture; BouncyCastle stays testFixturesImplementation on :core, so it is not on this runtime classpath unless declared here.
    implementation(libs.bouncycastle.pkix)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
