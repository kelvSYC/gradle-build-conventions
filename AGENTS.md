# Agent Guidance

Shared Gradle build conventions, published to GitHub Packages under group `com.kelvsyc.internal`.

This file is the agent guidance for this repository and is written to be model-neutral; `CLAUDE.md` only imports it. Keep tool- or vendor-specific instructions out of it.

## Modules

- `catalog` — publishes the shared version catalog, defined in `gradle/libs.versions.toml`: Gradle's standard location, which Dependabot requires to open update PRs, and which Gradle also registers as this build's own `libs`
- `platform` — platform BOM
- `settings-plugin` — settings plugin wiring repositories, the shared catalog, the default group and versionless `com.kelvsyc.internal.*` plugin requests into consumer builds (not the platform: consumers declare it); plugin IDs `com.kelvsyc.internal.settings` and `com.kelvsyc.internal.semver`
- `publishing-plugin` — GitHub Packages publishing convention; plugin ID `com.kelvsyc.internal.github-publishing`

## Publishing

The root `build.gradle.kts` declares the single publishing repository, `GitHubPackages`, on every subproject that applies `maven-publish`. Never declare any other publishing repository: any extra repository joins the `publish` lifecycle, which must stay the GitHub Packages release path only. Tests consume artifacts through `publishToMavenLocal` instead.

## Versioning and releases

Versions come from git tags via the semver settings plugin; on a tagged commit the version is the bare tag version. `.github/workflows/release.yml` runs on every push to `main` (skipped when only `.github/**`, `**/*.md` or `docs/**` changed), and releases the pushed commit in this order:

1. Reuse a `vMAJOR.MINOR.PATCH` tag already on the commit (for instance one pushed by hand), or else compute the next version from the latest such tag and create an annotated tag **locally only**.
2. Run `./gradlew check`, then `./gradlew publish` as a separate invocation (with the configuration cache, tasks of one invocation run in parallel, so `check publish` would not hold publishing back until every check passed). Semver reads the local tag, so the bare release version is published.
3. Push the tag, only if step 1 created it.

The tag is pushed last so that a failed check or publish never leaves a remote tag without artifacts. Next-version rules:

- no tag yet: `v0.1.0`
- default: patch bump
- label `release:minor` on the merged PR: minor bump
- label `release:major` on the merged PR: major bump

The labels must exist in the GitHub repository. They are read from the PR that produced the pushed commit (`gh api repos/{repo}/commits/{sha}/pulls`). Releases are serialized by `concurrency: release`.

## Dependencies and Dependabot

- Dependency repositories are declared only in the root `settings.gradle.kts` (`dependencyResolutionManagement`), with `RepositoriesMode.FAIL_ON_PROJECT_REPOS` so projects cannot add their own. Dependabot reads repositories from there (and from root `allprojects`/`subprojects` blocks), never from repositories a plugin adds; anything declared elsewhere makes its catalog lookups miss.
- The catalog's `gradle` version is the Gradle version these conventions are built and tested with; consumers' wrappers follow it. The catalog's `kotlin` is the compiler Kotlin and must equal Gradle's embedded Kotlin. `checkToolchainVersions` (part of `check`) fails if `gradle` differs from this repository's wrapper or `kotlin` from the embedded Kotlin.
- Dependabot groups routine Gradle updates (minor and patch) into one PR, because every merge to `main` is a release that each consumer then picks up. Majors, pre-1.0 libraries (where a "minor" can break) and the Gradle wrapper get their own PRs; extend `exclude-patterns` in `.github/dependabot.yml` when another library proves unreliable about semver. To drop one dependency from a grouped PR, comment `@dependabot ignore <dependency> minor version` (or `major version`, or just the name) on it; `@dependabot unignore <dependency>` restores it. Most catalog entries are used only by consumers, so this repository's CI does not exercise them: breakage shows up in consumers' builds.
- Gradle updates run weekly, and patch updates of the AWS SDK BOMs (`software.amazon.awssdk:bom`, `aws.sdk.kotlin:bom`) are ignored: they release almost daily, and every merge here becomes a release and a bump in each consumer. Their minor updates still come through.
- A Gradle upgrade is one change: the wrapper, the catalog's `gradle`, and the catalog's `kotlin` when the new Gradle embeds a different Kotlin. Dependabot ignores standalone Kotlin Gradle plugin bumps for that reason.

## Build Commands

```bash
./gradlew help
./gradlew build
./gradlew check
```
