plugins {
    `kotlin-dsl`
    `maven-publish`
    `jvm-test-suite`
}

group = "com.kelvsyc.internal"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.asm)
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
}

testing.suites.named<JvmTestSuite>("test") {
    useJUnitJupiter("5.14.1")
}
