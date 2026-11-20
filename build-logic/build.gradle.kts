plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(21)
}

// Self-hosted: compiles the same semver sources that settings-plugin publishes,
// so this repository can version itself without depending on a published artifact.
sourceSets.main {
    kotlin.srcDir("../settings-plugin/src/main/kotlin/com/kelvsyc/internal/semver")
}

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r")
}

gradlePlugin {
    plugins {
        create("semver") {
            id = "com.kelvsyc.internal.semver"
            implementationClass = "com.kelvsyc.internal.semver.SemverSettingsPlugin"
        }
    }
}
