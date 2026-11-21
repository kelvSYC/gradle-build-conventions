# gradle-build-conventions

Shared Gradle build conventions (version catalog, platform BOM, settings plugin, publishing plugin, and Kotlin and Dokka convention plugins) consumed by the rifflet, kotlin-tools and gradle-tools repositories. Published to GitHub Packages under group `com.kelvsyc.internal`.

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

The settings plugin also registers the shared catalog as `libs`, with the platform BOM at the same version added as `libs.conventions.platform`. The platform is not applied to any configuration: builds that want it declare it themselves (the shared Kotlin convention plugins do this for them):

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
| `com.kelvsyc.internal.kotlin-multiplatform-base` | `build.gradle.kts` | Kotlin Multiplatform without a target: compatibility floors, Kotlin toolchain checks, the shared platform, Kotest, `checkCommonMainPlatformImports`, Dependency Analysis |
| `com.kelvsyc.internal.kotlin-multiplatform-library` | `build.gradle.kts` | The base, plus published sources jars |
| `com.kelvsyc.internal.kotlin-multiplatform-jvm` | `build.gradle.kts` | The base, plus a `jvm` target released for the floor JVM and tested on it; with Dokka, a javadoc jar of the Dokka HTML |
| `com.kelvsyc.internal.kotlin-multiplatform-js` | `build.gradle.kts` | The base, plus a Node.js `js` library target with KSP and the Kotest Gradle plugin, and a pinned Node.js |
| `com.kelvsyc.internal.kotlin-jvm-library` | `build.gradle.kts` | Kotlin JVM library with the same floors and checks, sources jar and Kotest; a `maven` publication with `maven-publish` |
| `com.kelvsyc.internal.dokka` | `build.gradle.kts` | Dokka HTML documentation built by `assemble`, linking the JDK, the Kotlin standard library and the sources on GitHub; a javadoc jar of the Dokka Javadoc output for `java` projects that are not Kotlin Multiplatform |

### Kotlin compatibility floors

The Kotlin convention plugins compile for the oldest Kotlin and Java consumers are expected to use. The floors are shared defaults, set on the `kelvsycKotlin` extension, which a project can override:

```kotlin
kelvsycKotlin {
    languageVersion = KotlinVersion.KOTLIN_2_3 // also the API version; default 2.2
    coreLibrariesVersion = "2.3.0" // kotlin-stdlib in published metadata; default 2.2.21
    jvmRelease = 17 // Java release of the JVM output, and the JDK tests run on; default 17
    jvmToolchain = 25 // JDK that compiles; default 25
    nodeVersion = "22.12.0" // Node.js for JS tests; default 22.12.0
}
```

Compiler options set directly in a build script (`kotlin { compilerOptions { ... } }`) win over the extension. On JVM targets, compilation fails if the API version is above the `major.minor` of `coreLibrariesVersion`, since the published `kotlin-stdlib` would lack API the output may use.

`coreLibrariesVersion` is the one floor not wired lazily, because the Kotlin Gradle plugin's `kotlin.coreLibrariesVersion` is a plain value: the extension's value is copied to it after the project is evaluated, unless the build script set `kotlin.coreLibrariesVersion` directly, which always wins. Set the extension's value during evaluation, not in an `afterEvaluate` block, and note that `kotlin.coreLibrariesVersion` read during evaluation (for example in `kotlin("reflect", kotlin.coreLibrariesVersion)`) still holds the default; set `kotlin.coreLibrariesVersion` directly when the build script reads it.

The Kotlin Gradle plugin these conventions apply is the compiler, fixed to the catalog's `kotlin`. Builds applying them fail if the applied Kotlin Gradle plugin differs from it, or if another `org.jetbrains.kotlin` artifact on the build script classpath differs from the plugin.

Builds with their own convention plugins that build on the shared Kotlin conventions depend on `libs.conventions.kotlin.plugin` (and `libs.conventions.dokka.plugin` for the Dokka convention), both at the settings plugin's version.

### Dokka

The Dokka convention also applies to projects without Kotlin or Java, such as a documentation aggregator. Each declaration links to its source at `https://github.com/<owner>/<repo>/blob/<commit>/<path>`: the repository comes from the `origin` git remote, the commit is `HEAD`, and the path is relative to the root project of the outermost build (the composite's root). Without a GitHub `origin` there are no source links.

The JDK documentation linked is that of the release the output targets, not the JDK that compiles it: the `kelvsycKotlin` extension's `jvmRelease` with the Kotlin Multiplatform JVM convention, otherwise the release `compileJava` targets, falling back to the Java toolchain's version, then to the JDK running Gradle. A build script overrides it per source set:

```kotlin
dokka {
    dokkaSourceSets.configureEach {
        jdkVersion.set(21)
    }
}
```

Dokka bundles Jackson, jsoup and FreeMarker at versions with known vulnerabilities. Dokka's own configurations (those named `dokka*`) resolve them at the shared catalog's patched versions, fixed when the plugin is built, and the plugin brings Jackson's BOM to the build script classpath; the project's own classpaths are left alone.

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
