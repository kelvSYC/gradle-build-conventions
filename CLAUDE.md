# CLAUDE.md

Shared Gradle build conventions, published to GitHub Packages under group `com.kelvsyc.internal`.

## Modules

- `catalog` — shared version catalog
- `platform` — platform BOM
- `settings-plugin` — settings plugin wiring the catalog/platform into consumer builds; plugin IDs `com.kelvsyc.internal.settings` and `com.kelvsyc.internal.semver`
- `publishing-plugin` — GitHub Packages publishing convention; plugin ID `com.kelvsyc.internal.github-publishing`

## Publishing

The root `build.gradle.kts` declares the single publishing repository, `GitHubPackages`, on every subproject that applies `maven-publish`. Never declare any other publishing repository: any extra repository joins the `publish` lifecycle, which must stay the GitHub Packages release path only. Tests consume artifacts through `publishToMavenLocal` instead.

## Versioning and releases

Versions come from git tags via the semver settings plugin; on a tagged commit the version is the bare tag version. `.github/workflows/release.yml` runs on every push to `main` (skipped when only `.github/**`, `**/*.md` or `docs/**` changed), computes the next version from the latest `vMAJOR.MINOR.PATCH` tag, creates an annotated tag on the pushed commit, pushes it and runs `./gradlew publish`:

- no tag yet: `v0.1.0`
- default: patch bump
- label `release:minor` on the merged PR: minor bump
- label `release:major` on the merged PR: major bump

The labels are read from the PR that produced the pushed commit (`gh api repos/{repo}/commits/{sha}/pulls`). Releases are serialized by `concurrency: release`.

## Build Commands

```bash
./gradlew help
./gradlew build
./gradlew check
```
