#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
URL="https://raw.githubusercontent.com/gradle/gradle/v9.7.1/gradle/wrapper/gradle-wrapper.jar"
mkdir -p "$(dirname "$JAR")"
if [ -f "$JAR" ]; then
  echo "gradle-wrapper.jar bestaat al."
  exit 0
fi
echo "Download Gradle wrapper 9.7.1..."
curl -fL "$URL" -o "$JAR"
echo "Klaar: $JAR"
