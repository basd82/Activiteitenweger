@echo off
set DIR=%~dp0
if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
  echo gradle-wrapper.jar ontbreekt. Run bootstrap-gradle-wrapper.sh op macOS/Linux of plaats de wrapper jar handmatig.
  exit /b 1
)
java %JAVA_OPTS% -Dorg.gradle.appname=gradlew -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
