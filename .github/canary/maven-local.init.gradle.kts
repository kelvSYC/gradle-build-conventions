// Canary init script: every build of the consumer (including its included builds) resolves gradle-build-conventions'
// artifacts from Maven Local too, so the consumer builds against conventions published there but not yet released.
// Only the com.kelvsyc.internal groups come from Maven Local; everything else resolves as usual.
gradle.beforeSettings {
    val conventionsOnly: Action<MavenArtifactRepository> = Action {
        content { includeGroupByRegex("""com\.kelvsyc\.internal(\..*)?""") }
    }
    pluginManagement.repositories.mavenLocal(conventionsOnly)
    dependencyResolutionManagement.repositories.mavenLocal(conventionsOnly)
}
