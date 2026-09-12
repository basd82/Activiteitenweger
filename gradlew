#!/bin/sh
set -eu
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar ontbreekt. Voer eerst uit: ./bootstrap-gradle-wrapper.sh" >&2
  exit 1
fi
exec java ${JAVA_OPTS:-} -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
