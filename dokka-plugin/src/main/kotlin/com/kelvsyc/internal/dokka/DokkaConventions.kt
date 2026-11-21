package com.kelvsyc.internal.dokka

import java.util.Properties

/**
 * Facts about this release of the shared Dokka convention, fixed when the plugin was built: the shared catalog's
 * patched versions of the libraries Dokka bundles at vulnerable versions.
 */
internal object DokkaConventions {
    private val properties: Properties by lazy {
        val stream = DokkaConventions::class.java.getResourceAsStream("dokka-conventions.properties")
            ?: error("dokka-conventions.properties is missing from the shared Dokka convention plugin")
        stream.use { Properties().apply { load(it) } }
    }

    /** The version of every Jackson module except `jackson-annotations`, the shared catalog's `jackson`. */
    val JACKSON: String by lazy { property("jackson") }

    /** The shared catalog's `jsoup`. */
    val JSOUP: String by lazy { property("jsoup") }

    /** The shared catalog's `freemarker`. */
    val FREEMARKER: String by lazy { property("freemarker") }

    private fun property(name: String): String =
        properties.getProperty(name) ?: error("dokka-conventions.properties does not define '$name'")
}
