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

Publishing builds additionally apply the publishing convention in their `build.gradle.kts`. Leave out the version: the settings plugin resolves every versionless plugin request in exactly the `com.kelvsyc.internal` namespace at its own version, which keeps all shared plugins in step (they share classes, so mixing versions is unsupported). Plugins in a longer namespace, such as a build's own `com.kelvsyc.internal.kotlin-tools.*` conventions, are left alone:

```kotlin
plugins {
    id("com.kelvsyc.internal.github-publishing")
}
```

The settings plugin also registers the shared catalog as `libs`, with the platform BOM at the same version added as `libs.conventions.platform`. The platform is not applied to any configuration: builds that want it declare it themselves (the shared Kotlin convention plugins will do this for them):

```kotlin
dependencies {
    implementation(platform(libs.conventions.platform))
}
```

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

## Keeping the pin up to date

The settings plugin adds its repositories programmatically, and Dependabot cannot see repositories added by plugins. A consumer's `.github/dependabot.yml` therefore declares every GitHub Packages repository it resolves from as a registry, with a token that can read packages (a classic personal access token with `read:packages`; GitHub Packages does not accept fine-grained tokens), stored as the Dependabot secret `DEPENDABOT_PACKAGES_TOKEN`:

```yaml
registries:
  gradle-build-conventions:
    type: maven-repository
    url: https://maven.pkg.github.com/kelvSYC/gradle-build-conventions
    username: kelvSYC
    password: ${{secrets.DEPENDABOT_PACKAGES_TOKEN}}
  # …one entry per other GitHub Packages repository, e.g. kotlin-tools
updates:
  - package-ecosystem: gradle
    directory: /
    registries:
      - gradle-build-conventions
    schedule:
      interval: daily
```

Dependabot then proposes updates to the `com.kelvsyc.internal.settings` version in `settings.gradle.kts`. The secret name is the only coupling point: the token behind it can be replaced (rotated, or a different kind of credential) without touching the configuration.

## Releases

Every push to `main` that touches more than `.github/`, `*.md` or `docs/` is released automatically: the latest `v*` tag is bumped by patch, or by minor/major when the merged pull request carries the label `release:minor` or `release:major`. The first release is `v0.1.0`. Both labels must exist in the repository before they can be applied to a pull request.
