package com.kelvsyc.internal.kotlin

import java.util.Properties

/**
 * Facts about this release of the shared Kotlin conventions, fixed when the plugins were built.
 */
object KotlinConventions {
    private val properties: Properties by lazy {
        val stream = KotlinConventions::class.java.getResourceAsStream("kotlin-conventions.properties")
            ?: error("kotlin-conventions.properties is missing from the shared Kotlin convention plugins")
        stream.use { Properties().apply { load(it) } }
    }

    /**
     * The version of the shared build conventions these plugins were published as. The shared platform is published
     * at the same version.
     */
    val VERSION: String by lazy { property("version") }

    /**
     * The compiler Kotlin version: the shared catalog's `kotlin`, which the applied Kotlin Gradle plugin must equal.
     */
    val KOTLIN: String by lazy { property("kotlin") }

    /** The coordinates of the shared platform at [VERSION]. */
    val PLATFORM: String by lazy { "com.kelvsyc.internal:platform:$VERSION" }

    private fun property(name: String): String =
        properties.getProperty(name) ?: error("kotlin-conventions.properties does not define '$name'")
}
