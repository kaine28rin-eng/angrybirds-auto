#!/bin/sh

#=============================================================================
# Gradle start up script for UN*X
#=============================================================================

# Set JAVA_HOME to JDK 17 if not already set
if [ -z "$JAVA_HOME" ] ; then
  if [ -x "/opt/jdk17/bin/java" ] ; then
    JAVA_HOME=/opt/jdk17
  fi
fi
export JAVA_HOME

# Determine the script's location
APP_HOME=$(cd "$(dirname "$0")" || exit)
cd "$APP_HOME" || exit
APP_HOME=$(pwd)

# Wrapper jar location
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -r "$WRAPPER_JAR" ] ; then
  echo "ERROR: Cannot find $WRAPPER_JAR" >&2
  exit 1
fi

# Java command
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] ; then
  JAVA_CMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1 ; then
  JAVA_CMD="java"
else
  echo "ERROR: No Java executable found." >&2
  exit 1
fi

# Run Gradle wrapper
exec "$JAVA_CMD" -Xmx64m -XX:MaxMetaspaceSize=256m -XX:+HeapDumpOnOutOfMemoryError -cp "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
