package com.kelvsyc.internal.kotlin

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * The Gradle property that turns on public-API (ABI) validation in the library conventions: `true` turns it on, `false`
 * or an absent property leaves it off.
 */
const val ABI_VALIDATION_PROPERTY: String = "com.kelvsyc.kotlin.abiValidation"

/**
 * Turns on the Kotlin Gradle plugin's ABI validation when the build sets [ABI_VALIDATION_PROPERTY] to `true`, unless
 * the project has a Kotlin/Native target. The Kotlin plugin then makes `check` run `checkKotlinAbi` against the
 * reference dumps in `api/`, which `updateKotlinAbi` writes.
 *
 * Native modules are skipped because the native convention declares only the build host's target, so the klib dump,
 * which records its targets, would differ between hosts. The decision is made after evaluation, so native targets
 * added after the library plugin (by the native plugin or the build script) are seen; the Kotlin plugin only decides
 * whether to create its ABI tasks at its `ReadyForExecution` stage, which comes later.
 */
fun Project.configureAbiValidation() {
    when (val value = providers.gradleProperty(ABI_VALIDATION_PROPERTY).orNull) {
        null, "false" -> return
        "true" -> Unit
        else -> error("$value is not a supported value for $ABI_VALIDATION_PROPERTY; use true or false")
    }
    afterEvaluate {
        val nativeTargets = extensions.findByType(KotlinMultiplatformExtension::class.java)
            ?.targets?.withType(KotlinNativeTarget::class.java)?.names.orEmpty()
        if (nativeTargets.isNotEmpty()) {
            logger.info(
                "ABI validation is skipped for {}: it has Kotlin/Native targets ({}), and their klib dump depends on " +
                    "the build host.",
                path,
                nativeTargets.sorted().joinToString(),
            )
        } else {
            @OptIn(ExperimentalAbiValidation::class)
            extensions.getByType(KotlinProjectExtension::class.java).abiValidation()
        }
    }
}
