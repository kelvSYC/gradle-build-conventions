package com.kelvsyc.internal.settings

import java.util.Properties

/**
 * Facts about this release of the shared build conventions.
 */
object SharedConventions {
    /**
     * The version of the shared build conventions this plugin was published as. The shared
     * catalog and platform are published at the same version.
     */
    val VERSION: String by lazy {
        val stream = SharedConventions::class.java.getResourceAsStream("version.properties")
            ?: error("version.properties is missing from the shared settings plugin")
        val properties = stream.use { Properties().apply { load(it) } }
        properties.getProperty("version") ?: error("version.properties does not define 'version'")
    }
}
