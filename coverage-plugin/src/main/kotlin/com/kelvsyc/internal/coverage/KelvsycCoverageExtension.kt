package com.kelvsyc.internal.coverage

import org.gradle.api.provider.Property

/**
 * The `kelvsycCoverage` extension: thresholds of the changed-code check, `verifyChangedCoverage`.
 */
abstract class KelvsycCoverageExtension {
    /**
     * The fraction of branches on changed lines that tests must cover. Defaults to 0.75.
     */
    abstract val branchFloor: Property<Double>

    /**
     * The number of branches on changed lines from which [branchFloor] applies; fewer branches always pass. Defaults
     * to 4.
     */
    abstract val minimumBranches: Property<Int>
}
