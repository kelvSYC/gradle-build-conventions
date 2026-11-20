# CLAUDE.md

Shared Gradle build conventions, published to GitHub Packages under group `com.kelvsyc.internal`.

## Modules

- `catalog` — shared version catalog
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

## Build Commands

```bash
./gradlew help
./gradlew build
./gradlew check
```
