#!/usr/bin/env bash
# Builds a consumer of gradle-build-conventions against the conventions published to Maven Local
# (`./gradlew publishToMavenLocal` in this repository first).
#
# Usage: canary.sh <consumer-dir> <conventions-version> [gradle-task...]   (default task: :check)
#
# Points the consumer's single pin (`gradle-build-conventions` in gradle/libs.versions.toml) at the given version
# and runs the consumer's build with an init script that adds Maven Local. Exits with the consumer build's status.
set -euo pipefail

consumer=$1
version=$2
shift 2
if [ "$#" -eq 0 ]; then set -- :check; fi

init_script="$(cd "$(dirname "$0")/../canary" && pwd)/maven-local.init.gradle.kts"
catalog="$consumer/gradle/libs.versions.toml"

if ! grep -qE '^gradle-build-conventions *= *"' "$catalog"; then
    echo "No gradle-build-conventions pin in $catalog" >&2
    exit 1
fi
sed -i.bak -E "s/^(gradle-build-conventions *= *)\"[^\"]*\"/\1\"$version\"/" "$catalog"
rm "$catalog.bak"
echo "Building $consumer against gradle-build-conventions $version"

cd "$consumer"
exec ./gradlew --init-script "$init_script" -Dsemver.stage=snapshot -Dsemver.scope=patch "$@"
