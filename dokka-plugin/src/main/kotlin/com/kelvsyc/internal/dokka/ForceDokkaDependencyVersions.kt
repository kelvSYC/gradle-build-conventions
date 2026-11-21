package com.kelvsyc.internal.dokka

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencyResolveDetails

/**
 * Forces the patched versions of Jackson, jsoup and FreeMarker on Dokka's own configurations (those named `dokka*`),
 * and only there: the project's compile and runtime classpaths are left alone.
 *
 * `dokka-core` and `dokka-base` bring these libraries at vulnerable versions. Dokka populates its configurations
 * itself and they accept no declared dependencies, so a platform cannot reach them, and Gradle's alignment of the
 * Jackson modules through `jackson-bom` does not reliably raise every module either.
 */
internal fun Project.forceDokkaDependencyVersions() {
    val force = ForceDokkaDependencyVersions(DokkaConventions.JACKSON, DokkaConventions.JSOUP, DokkaConventions.FREEMARKER)
    configurations.matching { it.name.startsWith("dokka") }.configureEach {
        resolutionStrategy.eachDependency(force)
    }
}

private class ForceDokkaDependencyVersions(
    private val jackson: String,
    private val jsoup: String,
    private val freemarker: String,
) : Action<DependencyResolveDetails> {
    override fun execute(details: DependencyResolveDetails) {
        val requested = details.requested
        when {
            // jackson-annotations is versioned major.minor since Jackson 2.20; the forced jackson-bom supplies it.
            requested.group.startsWith("com.fasterxml.jackson") && requested.name != "jackson-annotations" ->
                details.useVersion(jackson)
            requested.group == "org.jsoup" && requested.name == "jsoup" -> details.useVersion(jsoup)
            requested.group == "org.freemarker" && requested.name == "freemarker" -> details.useVersion(freemarker)
        }
    }
}
