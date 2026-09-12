#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT"

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  ./bootstrap-gradle-wrapper.sh
fi

echo "== Android debug build =="
./gradlew --no-daemon :androidApp:assembleDebug

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)
    echo "== iOS Simulator ARM64 framework =="
    ./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
    ;;
  Darwin-x86_64)
    echo "== iOS Simulator x64 framework =="
    ./gradlew --no-daemon :shared:linkDebugFrameworkIosX64
    ;;
  *)
    echo "iOS compilecheck overgeslagen: hiervoor is macOS/Xcode nodig."
    ;;
esac

echo "Project compilecheck klaar."
