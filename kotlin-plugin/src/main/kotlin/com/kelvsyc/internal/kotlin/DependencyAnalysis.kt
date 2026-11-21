package com.kelvsyc.internal.kotlin

import com.autonomousapps.DependencyAnalysisExtension
import com.autonomousapps.DependencyAnalysisSubExtension
import com.autonomousapps.extension.ProjectIssueHandler
import org.gradle.api.Action
import org.gradle.api.Project

/**
 * Applies the Dependency Analysis plugin, failing its reports (`projectHealth`, `buildHealth`) on used transitive,
 * unused and wrongly configured dependencies. The reports are not part of `check`.
 *
 * `io.kotest:kotest-common` is exempt from the used-transitive rule: the Kotest modules declared by the conventions
 * expose it.
 */
internal fun Project.applyDependencyAnalysis() {
    pluginManager.apply("com.autonomousapps.dependency-analysis")

    val rules = Action<ProjectIssueHandler> {
        onUsedTransitiveDependencies {
            severity("fail")
            exclude("io.kotest:kotest-common")
        }
        onUnusedDependencies { severity("fail") }
        onIncorrectConfiguration { severity("fail") }
    }

    // The root project of a build gets the build-wide extension, every other project a per-project one.
    when (val extension = extensions.getByName("dependencyAnalysis")) {
        is DependencyAnalysisExtension -> extension.issues { all(rules) }
        is DependencyAnalysisSubExtension -> extension.issues(rules)
        // Never silently drop the rules: a Dependency Analysis plugin with other extension types needs an update here.
        else -> error(
            "Unexpected Dependency Analysis extension type ${extension.javaClass.name} in $path; " +
                "the shared Kotlin conventions cannot apply their dependency rules to it.",
        )
    }
}
