package com.kelvsyc.internal.kover

import kotlinx.kover.gradle.plugin.KoverGradlePlugin
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Kover convention: applies Kover at the shared catalog's `kover` version and keeps its verification out of `check`.
 *
 * Kover measures common and JVM source sets only; JS and native sources are not covered, so a JS-only project gets
 * empty reports. No rules, filters or report tasks are configured, and nothing is wired into `check`.
 */
class KoverConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(KoverGradlePlugin::class.java)
        project.extensions.getByType(KoverProjectExtension::class.java).reports.total {
            verify.onCheck.set(false)
        }
    }
}
