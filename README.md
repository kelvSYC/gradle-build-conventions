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

Publishing builds additionally apply the publishing convention in their `build.gradle.kts`. Leave out the version: the settings plugin resolves every versionless `com.kelvsyc.internal.*` plugin request at its own version, which keeps all shared plugins in step (they share classes, so mixing versions is unsupported):

```kotlin
plugins {
    id("com.kelvsyc.internal.github-publishing")
}
```

The settings plugin also registers the shared catalog as `libs`. The platform BOM is not wired in: builds that want it declare `platform("com.kelvsyc.internal:platform:<version>")` themselves (the shared Kotlin convention plugins will do this for them).

Every project whose group is still Gradle's default gets the group `com.kelvsyc`; projects that set their own group (in `gradle.properties`, a build script or a parent's `allprojects`/`subprojects` block) keep it. Change the default in `settings.gradle.kts`:

```kotlin
kelvsyc {
    group = "com.kelvsyc.rifflet"
}
```

| Plugin ID | Applied in | Purpose |
|---|---|---|
| `com.kelvsyc.internal.settings` | `settings.gradle.kts` | Repositories, the shared catalog as `libs`, the default project group, semver, and versions for the other shared plugins |
| `com.kelvsyc.internal.semver` | `settings.gradle.kts` | Derives the project version from git tags |
| `com.kelvsyc.internal.github-publishing` | `build.gradle.kts` | Publishes to GitHub Packages |

## Releases

Every push to `main` that touches more than `.github/`, `*.md` or `docs/` is released automatically: the latest `v*` tag is bumped by patch, or by minor/major when the merged pull request carries the label `release:minor` or `release:major`. The first release is `v0.1.0`. Both labels must exist in the repository before they can be applied to a pull request.
