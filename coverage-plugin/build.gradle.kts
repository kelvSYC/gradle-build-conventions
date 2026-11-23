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

val fixtures = sourceSets.register("fixtures")

tasks.named<Test>("test") {
    val compileFixtures = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileFixturesKotlin")
    dependsOn(compileFixtures)
    systemProperty("fixtures.classes", compileFixtures.get().destinationDirectory.get().asFile.path)
}
