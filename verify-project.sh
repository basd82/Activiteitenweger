#!/bin/sh
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
    ;;
  Darwin-x86_64)
    echo "iOS compilecheck overgeslagen: Compose Multiplatform 1.12.0 gebruikt geen iosX64-target meer."
    ;;
  *)
    echo "iOS compilecheck overgeslagen: hiervoor is macOS/Xcode op Apple Silicon nodig."
    ;;
esac

echo "Project compilecheck klaar."
