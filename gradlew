#!/usr/bin/env sh
# Small, self-bootstrapping Gradle launcher kept in the repository so a clean
# machine does not need Gradle pre-installed. JDK and Android SDK are still
# required; see README.md.
set -eu

gradle_version="8.11.1"

if [ -n "${GRADLE_BIN:-}" ]; then
    exec "$GRADLE_BIN" "$@"
fi

if command -v gradle >/dev/null 2>&1 && gradle --version 2>/dev/null | grep -q "Gradle $gradle_version"; then
    exec gradle "$@"
fi

cache_root="${GRADLE_USER_HOME:-${XDG_CACHE_HOME:-${TMPDIR:-/tmp}}}/pixel-watch-heart-rate-alert"
install_dir="$cache_root/gradle-$gradle_version"
gradle_bin="$install_dir/bin/gradle"

if [ ! -x "$gradle_bin" ]; then
    mkdir -p "$cache_root"
    archive="$cache_root/gradle-$gradle_version-bin.zip"
    url="https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip"

    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --output "$archive" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget --output-document="$archive" "$url"
    else
        echo "This build needs curl or wget to download Gradle $gradle_version." >&2
        exit 1
    fi

    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$archive" -d "$cache_root"
    else
        echo "This build needs unzip to unpack Gradle $gradle_version." >&2
        exit 1
    fi
fi

exec "$gradle_bin" "$@"
