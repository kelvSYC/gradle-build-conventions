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
 * Some modules are exempt from the used-transitive rule because the modules that expose them are the ones meant to be
 * declared: `io.kotest:kotest-common` and `io.kotest:kotest-assertions-shared` (exposed by the Kotest modules the
 * conventions declare), and `io.mockk:mockk-core` and `io.mockk:mockk-dsl` (exposed by `io.mockk:mockk`).
 */
fun Project.applyDependencyAnalysis() {
    pluginManager.apply("com.autonomousapps.dependency-analysis")

    val rules = Action<ProjectIssueHandler> { handler ->
        handler.onUsedTransitiveDependencies { issue ->
            issue.severity("fail")
            issue.exclude(
                "io.kotest:kotest-assertions-shared",
                "io.kotest:kotest-common",
                "io.mockk:mockk-core",
                "io.mockk:mockk-dsl",
            )
        }
        handler.onUnusedDependencies { issue -> issue.severity("fail") }
        handler.onIncorrectConfiguration { issue -> issue.severity("fail") }
    }

    // The root project of a build gets the build-wide extension, every other project a per-project one.
    when (val extension = extensions.getByName("dependencyAnalysis")) {
        is DependencyAnalysisExtension -> extension.issues { issues -> issues.all(rules) }
        is DependencyAnalysisSubExtension -> extension.issues(rules)
        // Never silently drop the rules: a Dependency Analysis plugin with other extension types needs an update here.
        else -> error(
            "Unexpected Dependency Analysis extension type ${extension.javaClass.name} in $path; " +
                "the shared Kotlin conventions cannot apply their dependency rules to it.",
        )
    }
}
