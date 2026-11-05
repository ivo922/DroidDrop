import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.bouncycastle.pkix)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    val fixturesDir = rootProject.projectDir.resolve("../protocol/fixtures")
    useJUnitPlatform()
    inputs.dir(fixturesDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("droiddrop.fixtures", fixturesDir.absolutePath)
}
