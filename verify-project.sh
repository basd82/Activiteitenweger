#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 Bas van den Dikkenberg

set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT"

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  ./bootstrap-gradle-wrapper.sh
fi

echo "== Android debug build =="
./gradlew --no-daemon --warning-mode all :androidApp:assembleDebug

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)
    echo "== iOS Simulator ARM64 framework =="
    ./gradlew --no-daemon --warning-mode all :shared:linkDebugFrameworkIosSimulatorArm64

    echo "== iOS Device ARM64 Kotlin compile =="
    ./gradlew --no-daemon --warning-mode all :shared:compileKotlinIosArm64
    ;;
  Darwin-x86_64)
    echo "iOS compilecheck overgeslagen: dit project gebruikt geen iosX64-target."
    ;;
  *)
    echo "iOS compilecheck overgeslagen: hiervoor is macOS/Xcode op Apple Silicon nodig."
    ;;
esac

echo "Project compilecheck klaar."
