plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r")

    testImplementation(platform("org.junit:junit-bom:5.14.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("semver") {
            id = "com.kelvsyc.internal.semver"
            implementationClass = "com.kelvsyc.internal.semver.SemverSettingsPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
