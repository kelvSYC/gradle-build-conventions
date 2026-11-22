// Helpers shared by the Kotlin convention plugins (kotlin-plugin) and the Gradle-plugin convention plugins
// (kotlin-gradle-plugin-conventions): Dependency Analysis rules, the Kotlin toolchain checks and the facts generated
// into the jar. It declares no plugin, so neither convention artifact carries the other's dependencies.

plugins {
    // Not `kotlin-dsl`, which brings `java-gradle-plugin`: this artifact has no plugins to describe. Without the Kotlin
    // DSL's SAM-with-receiver support, lambdas passed as `Action`s take their argument explicitly.
    `embedded-kotlin`
    `java-library`
    `maven-publish`
}

group = "com.kelvsyc.internal"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(gradleApi())
    // The applied Kotlin Gradle plugin is the compiler version, so it must be exactly the catalog's `kotlin` (Gradle's
    // embedded Kotlin): checkKotlinPluginVersion fails consumer builds otherwise.
    implementation("${libs.kotlin.plugin.get().module}:${libs.versions.kotlin.get()}")
    implementation(libs.dependency.analysis.plugin)
}

// Embeds this build's version and the compiler Kotlin version, so the plugins need nothing from the consumer's catalog.
val generateKotlinConventionsProperties = tasks.register<WriteProperties>("generateKotlinConventionsProperties") {
    destinationFile = layout.buildDirectory.file(
        "generated/kotlinConventions/com/kelvsyc/internal/kotlin/kotlin-conventions.properties",
    )
    property("version", project.version.toString())
    property("kotlin", libs.versions.kotlin.get())
}

sourceSets.named("main") {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/kotlinConventions")).builtBy(generateKotlinConventionsProperties),
    )
}

publishing.publications.register<MavenPublication>("maven") {
    from(components["java"])
}
