# gradle-build-conventions

Shared Gradle build conventions (version catalog, platform BOM, settings plugin, publishing plugin, and Kotlin, Gradle-plugin, Dokka and coverage convention plugins) consumed by the rifflet, kotlin-tools and gradle-tools repositories. Published to GitHub Packages under group `com.kelvsyc.internal`.

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

The convention publishes to the GitHub Packages repository named by the `origin` git remote. Its credentials are the `GitHubPackagesUsername` and `GitHubPackagesPassword` Gradle properties, for example from the environment as `ORG_GRADLE_PROJECT_GitHubPackagesUsername` and `ORG_GRADLE_PROJECT_GitHubPackagesPassword` (in GitHub Actions, the actor and a token with `write:packages`). They are separate from the resolution credentials above (`gpr.user`/`gpr.key`, else `GITHUB_ACTOR`/`GITHUB_TOKEN`), which the settings plugin uses unchanged. Gradle resolves the publishing credentials only for builds that publish to GitHub Packages, and keeps them out of the configuration cache; a publishing build without them fails before running any task, naming both properties. Without a GitHub `origin`, publishing fails because of the missing remote instead.

`check` runs `verifyPomDependencyVersions`, which fails if the generated POM of any Maven publication has a dependency without a version (the internal platform is stripped from the POMs, so versions that only it supplied would otherwise reach consumers unresolvable). It reads the POMs rather than the Gradle module metadata because generating module metadata builds every publication artifact, including documentation jars, which would put them in every `check`. The module metadata check (`RequireDependencyVersions`) still runs whenever module metadata is generated, so `publish` is guarded too.

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
| `com.kelvsyc.internal.kotlin-multiplatform-jvm` | `build.gradle.kts` | The base, plus a `jvm` target released for the floor JVM and tested on it; with Dokka, a javadoc jar (Dokka Javadoc when every target is a JVM target, else Dokka HTML; see Dokka below) |
| `com.kelvsyc.internal.kotlin-multiplatform-js` | `build.gradle.kts` | The base, plus a Node.js `js` library target with KSP and the Kotest Gradle plugin, and a pinned Node.js |
| `com.kelvsyc.internal.kotlin-multiplatform-native` | `build.gradle.kts` | The base, plus KSP, the Kotest Gradle plugin and the build host's native target (`linuxX64` on Linux, `macosArm64` on macOS, `mingwX64` on Windows), with `kotlinx.cinterop.ExperimentalForeignApi` opted in on every native compilation. Each host builds and publishes only its own native target |
| `com.kelvsyc.internal.kotlin-jvm-library` | `build.gradle.kts` | Kotlin JVM library with the same floors and checks, sources jar and Kotest; a `maven` publication with `maven-publish` |
| `com.kelvsyc.internal.kotlin-gradle-library` | `build.gradle.kts` | Kotlin JVM library compiled against the Gradle API of its Gradle tier (see Gradle-plugin conventions below), with `verifyGradleLinkage`, sources jar, Kotest and mockk-ready tests; a `maven` publication with `maven-publish` |
| `com.kelvsyc.internal.kotlin-gradle-plugin` | `build.gradle.kts` | The same for Gradle plugins, with `kotlin-dsl` and `java-gradle-plugin` (which publishes the plugin and its markers) |
| `com.kelvsyc.internal.dokka` | `build.gradle.kts` | Dokka HTML documentation built by `assemble`, linking the JDK, the Kotlin standard library and the sources on GitHub; a javadoc jar of the Dokka Javadoc output for `java` projects that are not Kotlin Multiplatform |
| `com.kelvsyc.internal.coverage` | `build.gradle.kts` | JaCoCo on every test task (and TestKit builds), `coverageReport`, and `verifyChangedCoverage`, which fails when changed code is not tested (see Coverage below) |

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

Builds with their own convention plugins that build on the shared Kotlin conventions depend on `libs.conventions.kotlin.plugin` (and `libs.conventions.dokka.plugin` for the Dokka convention, `libs.conventions.kotlin.gradle.plugin.conventions` for the Gradle-plugin conventions, `libs.conventions.coverage.plugin` for the coverage convention), all at the settings plugin's version. Each alias is `conventions-` followed by the artifact name.

### Gradle-plugin conventions

`com.kelvsyc.internal.kotlin-gradle-library` and `com.kelvsyc.internal.kotlin-gradle-plugin` are for code that runs inside Gradle: Gradle plugins and the libraries they use. Their published code (`main`) is compiled for the oldest Gradle it supports, its tier, declared per build with the Gradle property `com.kelvsyc.gradle.minimumGradle` in the build's `gradle.properties` (a subproject's `gradle.properties` is not read):

| `com.kelvsyc.gradle.minimumGradle` | Tier | Gradle API compiled against | Kotlin standard library and language version of `main` |
|---|---|---|---|
| absent or `8.14` | `GRADLE_8_14` | `org.gradle.experimental:gradle-public-api:8.14` | 2.0.21, Kotlin 2.0 |
| `9.0` | `GRADLE_9_0` | `org.gradle.experimental:gradle-public-api:9.0.0` | 2.2.0, Kotlin 2.2, with `-Xjspecify-annotations=warn` |

Any other value fails the build. The tier is read when the plugin is applied, and is available to build scripts as `kelvsycGradle.tier`. It also sets `org.gradle.plugin.api-version` to the tier's Gradle version on the published variants (unless the build script sets it itself) and on `main`'s compile classpath, and `verifyGradleLinkage` (part of `check`) fails if `main` or its runtime dependencies reference a Gradle or Kotlin standard library class, method or field missing from the tier's Gradle. The Gradle API comes from Gradle's `libs-releases` repository, which the shared settings plugin adds.

The rest does not depend on the tier:

- JDK 21 compiles, with no release override, so the bytecode and the published `org.gradle.jvm.version` are 21; tests run on JDK 21 too.
- Every compilation but `main`'s uses Kotlin 2.3. The language and API version are set on the compile tasks, so a build script overrides them there (`tasks.named<KotlinCompile>(...)`), not on the `kotlin` extension.
- Published dependencies exclude `kotlin-stdlib`, `kotlin-stdlib-jdk7`, `kotlin-stdlib-jdk8` and `kotlin-reflect`, which Gradle supplies; the library kind also sets `kotlin.stdlib.default.dependency=false`.
- Tests get the shared platform, Kotest (`kotest-assertions-core`, `kotest-framework-engine`, `kotest-runner-junit5`) on the JUnit Platform, the Gradle API of the Gradle running the build, and the JVM arguments mockk needs (`-XX:+EnableDynamicAgentLoading`, `-Dnet.bytebuddy.experimental=true`, `-javaagent:` with the `byte-buddy-agent` on the test classpath) plus `--add-opens=java.base/java.lang=ALL-UNNAMED` for `ProjectBuilder`.
- Dependency Analysis and the Kotlin toolchain checks are those of the Kotlin conventions. The project group is not set.

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

A Kotlin Multiplatform project with the `kotlin-multiplatform-jvm` convention gets a javadoc jar on its `jvm` publication, chosen once the project is evaluated. If every target is a JVM target (besides the common metadata), `org.jetbrains.dokka-javadoc` is applied and the jar holds Dokka's Javadoc output. With any other target (JS, native, Wasm, Android), the jar holds the HTML documentation instead, because Dokka's Javadoc format rejects source sets that are neither JVM nor common. Adding a non-JVM target to a JVM-only project therefore switches its javadoc jar from Javadoc to HTML.

Dokka bundles Jackson, jsoup and FreeMarker at versions with known vulnerabilities. Dokka's own configurations (those named `dokka*`) resolve them at the shared catalog's patched versions, fixed when the plugin is built, and the plugin brings Jackson's BOM to the build script classpath; the project's own classpaths are left alone.

### Coverage

`com.kelvsyc.internal.coverage` is applied next to a shared Kotlin convention:

```kotlin
plugins {
    id("com.kelvsyc.internal.kotlin-jvm-library") // or kotlin-multiplatform-jvm, kotlin-gradle-plugin, ...
    id("com.kelvsyc.internal.coverage")
}
```

In a composite build, apply it to every included build that applies a shared Kotlin convention, not only some of them. Builds whose plugin classpaths are identical share one classloader; applying the plugin in only some builds splits them, which raises daemon metaspace (see AGENTS.md).

It applies JaCoCo, at the shared catalog's `jacoco` version, to every test task (`test`, and `jvmTest` in Kotlin Multiplatform projects), and adds two tasks. Neither is part of `check`.

- `coverageReport` writes the JaCoCo XML and HTML reports to `build/reports/jacoco/coverageReport/`, from every `.exec` file in `build/jacoco/`, over the JVM main classes and their sources: `main` for Kotlin JVM and Gradle-plugin projects, and for Kotlin Multiplatform the JVM target's main compilation, which includes `commonMain` and `jvmMain`. It runs every test task first.
- `verifyChangedCoverage` checks the code changed since a diff base, and fails unless:
  1. every new or changed public declaration (public or protected; not `internal` and not compiler-generated) is executed by at least one test. A declaration counts as changed when any of its lines, signature or body, is;
  2. at least 75% of the branches on changed lines are covered, once the project's changed lines hold at least 4 branches;
  3. every exemption marker on a changed line gives a reason.

  Line coverage of changed lines is reported but never fails the check. The result is printed and written to `build/reports/coverage/verifyChangedCoverage.txt`. The check reads its own XML-only report (`verifyChangedCoverageReport`), so it never waits for the HTML report.

  A Kotlin Multiplatform project without a JVM target passes, listing its changed main sources as not measurable. A project that applies neither the Kotlin JVM nor the Kotlin Multiplatform plugin fails, rather than passing on an empty diff. Source files are matched to their classes by their `package` declaration, so a file whose directory omits part of its package is still checked.

The changes are the working tree, including uncommitted and untracked files, against the merge base of `HEAD` and `origin/main`, restricted to the project's main sources. Pass another base with the Gradle property `coverage.base` (`-Pcoverage.base=<ref>`); if git cannot find the merge base, the check fails rather than passing on an empty diff. In a shallow clone (such as a default `actions/checkout`), check out with `fetch-depth: 0` or fetch the base first.

To check coverage data that is already on disk, for example execution data and classes collected from CI shards, pass `-Pcoverage.useExistingData=true`: `coverageReport` then runs no tests, though it still compiles the main classes. Without any execution data the check reports that no test executed the project's code, rather than reading an earlier report.

Code that cannot be tested is exempted in the source, with a reason, so the exemption shows in the diff. At the end of a line, the marker exempts that line (and a declaration whose code starts on it). Alone on a line, it exempts what the next line of code starts, skipping blank lines, comments and annotations:

- a `class`, `interface` or `object`: all of its members, constructors and nested classes;
- a `fun` or `constructor`: that function, including its lambdas and local functions;
- an `init` block: the block's lines;
- anything else, including a `val` or `var` (which may be local): that line only.

```kotlin
// coverage-exempt: unreachable, the caller validates the input
fun fallback(): Nothing = error("unreachable")

fun home(): String {
    // coverage-exempt: always set on supported platforms
    val home = System.getenv("HOME") ?: error("no HOME")
    return home
}

val user = System.getenv("USER") ?: "unknown" // coverage-exempt: always set on supported platforms
```

A marker without a reason fails the check.

Some changed code cannot be measured, and is listed under "Not measurable" instead of failing the check: bodies of `inline` functions and properties, which JaCoCo attributes to their callers, and Kotlin Multiplatform sources compiled only for JS or native targets.

The thresholds are set per project:

```kotlin
kelvsycCoverage {
    branchFloor = 0.5 // default 0.75
    minimumBranches = 8 // default 4
}
```

In projects with `java-gradle-plugin` (such as `com.kelvsyc.internal.kotlin-gradle-plugin`), TestKit builds that use `withPluginClasspath()` run the plugin under test with JaCoCo too (through `io.github.gmazzo.gradle.testkit.jacoco`), and their coverage counts. A TestKit build records its data only if it applies the `jacoco-testkit-coverage` plugin, which `withPluginClasspath()` provides, in its settings or root build script:

```kotlin
// settings.gradle.kts of the TestKit build
plugins {
    id("jacoco-testkit-coverage")
}
```

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
