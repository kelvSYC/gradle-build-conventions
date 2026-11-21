# Agent Guidance

Shared Gradle build conventions, published to GitHub Packages under group `com.kelvsyc.internal`.

This file is the agent guidance for this repository and is written to be model-neutral; `CLAUDE.md` only imports it. Keep tool- or vendor-specific instructions out of it.

## Modules

- `catalog` — publishes the shared version catalog, defined in `gradle/libs.versions.toml`: Gradle's standard location, which Dependabot requires to open update PRs, and which Gradle also registers as this build's own `libs`
- `platform` — platform BOM
- `settings-plugin` — settings plugin wiring repositories, the shared catalog (plus the platform as `libs.conventions.platform`; consumers declare the dependency themselves), the default group and versionless plugin requests into consumer builds; plugin IDs `com.kelvsyc.internal.settings` and `com.kelvsyc.internal.semver`. Only versionless requests in exactly the `com.kelvsyc.internal` namespace get the shared version: consumers' own plugins in longer namespaces (e.g. `com.kelvsyc.internal.kotlin-tools.*`) must resolve as the consumer declares them.
- `publishing-plugin` — GitHub Packages publishing convention; plugin ID `com.kelvsyc.internal.github-publishing`
- `kotlin-plugin` — Kotlin convention plugins: `com.kelvsyc.internal.kotlin-multiplatform-base`, `-library`, `-jvm` and `-js`, and `com.kelvsyc.internal.kotlin-jvm-library`, with the `kelvsycKotlin` extension holding the shared compatibility floors (language/API version, `coreLibrariesVersion`, JVM release, JDK toolchain, Node.js version). The compiler Kotlin version (the catalog's `kotlin`) and this build's version are generated into the plugin jar at build time, so the plugins read nothing from a consumer's catalog: the Kotlin Gradle plugin dependency is pinned to the catalog's `kotlin`, and the versionless Kotest coordinates the plugins add are versioned by the shared platform they also add. Every consumer's build script classpath carries this module's dependencies (Kotlin Gradle plugin, KSP, the Kotest Gradle plugin, the Dependency Analysis plugin), and the plugins fail a build where any `org.jetbrains.kotlin` artifact there differs from the Kotlin Gradle plugin version, so a dependency bump that raises one breaks every consumer; the functional tests apply the plugins and catch it first.

## Publishing

The root `build.gradle.kts` declares the single publishing repository, `GitHubPackages`, on every subproject that applies `maven-publish`. Never declare any other publishing repository: any extra repository joins the `publish` lifecycle, which must stay the GitHub Packages release path only. Tests consume artifacts through `publishToMavenLocal` instead.

## Versioning and releases

Versions come from git tags via the semver settings plugin; on a tagged commit the version is the bare tag version. `.github/workflows/release.yml` runs on every push to `main` (skipped when only `.github/**`, `**/*.md` or `docs/**` changed), and releases the pushed commit in this order:

1. Reuse a `vMAJOR.MINOR.PATCH` tag already on the commit (for instance one pushed by hand), or else compute the next version from the latest such tag and create an annotated tag **locally only**.
2. Run `./gradlew check`, then `./gradlew publish` as a separate invocation (with the configuration cache, tasks of one invocation run in parallel, so `check publish` would not hold publishing back until every check passed). Semver reads the local tag, so the bare release version is published.
3. Push the tag, only if step 1 created it.

The tag is pushed last so that a failed check or publish never leaves a remote tag without artifacts. If only the tag push fails, the release is published but the tag is missing from the repository, and the next release would compute the same version and fail to publish it. Recover by pushing the tag by hand: `git tag -a vX.Y.Z -m "Release vX.Y.Z" <released commit>` and `git push origin vX.Y.Z`, with the version from the failed run's log. Re-running the workflow instead would try to publish the same version again.

Next-version rules:

- no tag yet: `v0.1.0`
- default: patch bump
- label `release:minor` on the merged PR: minor bump
- label `release:major` on the merged PR: major bump

The labels must exist in the GitHub repository. They are read from the PR that produced the pushed commit (`gh api repos/{repo}/commits/{sha}/pulls`). Releases are serialized by `concurrency: release`.

## Dependencies and Dependabot

- Dependency repositories are declared only in the root `settings.gradle.kts` (`dependencyResolutionManagement`), with `RepositoriesMode.FAIL_ON_PROJECT_REPOS` so projects cannot add their own. Dependabot reads repositories from there (and from root `allprojects`/`subprojects` blocks), never from repositories a plugin adds; anything declared elsewhere makes its catalog lookups miss.
- The catalog's `gradle` version is the Gradle version these conventions are built and tested with; consumers' wrappers follow it. The catalog's `kotlin` is the compiler Kotlin and must equal Gradle's embedded Kotlin. `checkToolchainVersions` (part of `check`) fails if the wrapper is not a stable Gradle release, if `gradle` differs from it, or if `kotlin` differs from the embedded Kotlin.
- Dependabot groups routine Gradle updates (minor and patch) into one PR, because every merge to `main` is a release that each consumer then picks up. Majors, pre-1.0 libraries (where a "minor" can break) and the Gradle wrapper get their own PRs; extend `exclude-patterns` in `.github/dependabot.yml` when another library proves unreliable about semver. To drop one dependency from a grouped PR, comment `@dependabot ignore <dependency> minor version` (or `major version`, or just the name) on it; `@dependabot unignore <dependency>` restores it. Most catalog entries are used only by consumers, so this repository's own tests do not exercise them; the canary (below) does.
- The grouped catalog PR (`catalog-minor-and-patch`) merges itself: `.github/workflows/dependabot-auto-merge.yml` enables GitHub auto-merge on it, so it merges once the required checks (`build` and every `canary (<consumer>)` job) pass, and the merge releases. Every other Dependabot PR (majors, the excluded libraries, the Gradle wrapper, GitHub Actions) waits for a person. Auto-merge is enabled with the `CONVENTIONS_TOKEN` personal access token, stored in both the Dependabot and Actions secrets (a run started by Dependabot sees only the former, one started by a person pushing to the PR only the latter), because a merge made with `GITHUB_TOKEN` triggers no workflows and would not release. Adding a consumer to the canary means adding its `canary (<repository>)` check to the required checks.
- Gradle updates run weekly, and patch updates of the AWS SDK BOMs (`software.amazon.awssdk:bom`, `aws.sdk.kotlin:bom`) are ignored: they release almost daily, and every merge here becomes a release and a bump in each consumer. Their minor updates still come through.
- A Gradle upgrade is one change: the wrapper, the catalog's `gradle`, and the catalog's `kotlin` when the new Gradle embeds a different Kotlin. Dependabot ignores standalone Kotlin Gradle plugin bumps for that reason. Its Gradle wrapper PRs change only the wrapper, so they fail `checkToolchainVersions` as opened: complete them in place by setting the catalog's `gradle` to the new version (and `kotlin` to the new embedded Kotlin, from the Gradle release notes) on the same branch.

## Canary

The `canary` job in `.github/workflows/build.yml` builds each consumer that has migrated to these conventions against the change under test, so a change that breaks a consumer fails here rather than after release. It publishes this repository to Maven Local, checks the consumer out, and runs `.github/scripts/canary.sh <consumer-dir> <version> [tasks…]`: the script points the consumer's single pin (`gradle-build-conventions` in its `gradle/libs.versions.toml`) at that version and runs the consumer's build with `.github/canary/maven-local.init.gradle.kts`, which adds Maven Local (restricted to the `com.kelvsyc.internal` groups) to every build's plugin and dependency repositories.

The consumers are kotlin-tools (`:check`) and rifflet (`:check consumerCompatibilityTest`: its fixture builds rifflet on the oldest supported Kotlin and Java, so a change to the shared floors that would break old consumers fails here). To cover a newly migrated consumer, add it to the job's `matrix.consumer` list with the tasks its own CI uses to verify a change, then add its `canary (<repository>)` check to the required checks. To reproduce a canary failure locally: `./gradlew publishToMavenLocal`, take the version from `./gradlew -q properties --property version`, then run `canary.sh` against a scratch checkout of the consumer (it edits the consumer's catalog).

## Build Commands

```bash
./gradlew help
./gradlew build
./gradlew check
```
