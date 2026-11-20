plugins {
    `version-catalog`
    `maven-publish`
}

group = "com.kelvsyc.internal"

// The shared catalog lives at Gradle's standard location (gradle/libs.versions.toml): Dependabot only updates
// catalogs there, and Gradle registers it as this build's own `libs` automatically.
catalog {
    versionCatalog {
        from(files(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml")))
    }
}

publishing {
    publications.create<MavenPublication>("catalog") {
        from(components["versionCatalog"])
    }
}
