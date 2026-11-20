plugins {
    `version-catalog`
    `maven-publish`
}

group = "com.kelvsyc.internal"

catalog {
    versionCatalog {
        from(files("libs.versions.toml"))
    }
}

publishing {
    publications {
        create<MavenPublication>("catalog") {
            from(components["versionCatalog"])
        }
    }
}
