# gradle-build-conventions

Shared Gradle build conventions (version catalog, platform BOM, settings plugin and publishing plugin) consumed by the rifflet, kotlin-tools and gradle-tools repositories. Published to GitHub Packages under group `com.kelvsyc.internal`.

## Consumer usage

The packages are served from GitHub Packages, which requires credentials even for reads. In the consumer's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/kelvSYC/gradle-build-conventions") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("com.kelvsyc.internal.settings") version "<version>"
}
```

Publishing builds additionally apply the publishing convention in their `build.gradle.kts`:

```kotlin
plugins {
    id("com.kelvsyc.internal.github-publishing")
}
```

| Plugin ID | Applied in | Purpose |
|---|---|---|
| `com.kelvsyc.internal.settings` | `settings.gradle.kts` | Wires the shared catalog and platform into the build |
| `com.kelvsyc.internal.semver` | `settings.gradle.kts` | Derives the project version from git tags |
| `com.kelvsyc.internal.github-publishing` | `build.gradle.kts` | Publishes to GitHub Packages |

## Releases

Every push to `main` that touches more than `.github/`, `*.md` or `docs/` is released automatically: the latest `v*` tag is bumped by patch, or by minor/major when the merged pull request carries the label `release:minor` or `release:major`. The first release is `v0.1.0`.
