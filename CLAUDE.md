# CLAUDE.md

Shared Gradle build conventions, published to GitHub Packages under group `com.kelvsyc.internal`.

## Modules

- `catalog` — shared version catalog
- `platform` — platform BOM
- `settings-plugin` — settings plugin wiring the catalog/platform into consumer builds
- `publishing-plugin` — GitHub Packages publishing convention

Never declare a publishing repository other than `GitHubPackages`: tests consume via `publishToMavenLocal`, since any extra repository joins the `publish` lifecycle.

## Build Commands

```bash
./gradlew help
./gradlew build
./gradlew check
```
