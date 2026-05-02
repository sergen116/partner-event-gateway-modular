#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script (minimal)
# Downloads maven-wrapper.jar on first run, then delegates to it.
# ----------------------------------------------------------------------------

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_PROPS="$DIR/.mvn/wrapper/maven-wrapper.properties"
WRAPPER_JAR="$DIR/.mvn/wrapper/maven-wrapper.jar"

if [ ! -f "$WRAPPER_PROPS" ]; then
    echo "ERROR: $WRAPPER_PROPS not found." >&2
    exit 1
fi

if [ ! -f "$WRAPPER_JAR" ]; then
    WRAPPER_URL=$(grep '^wrapperUrl=' "$WRAPPER_PROPS" | cut -d= -f2-)
    echo "Downloading maven-wrapper.jar from $WRAPPER_URL ..."
    if command -v curl >/dev/null 2>&1; then
        curl -sSfL "$WRAPPER_URL" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR"
    else
        echo "ERROR: neither curl nor wget available." >&2
        exit 1
    fi
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

exec "$JAVA_BIN" \
    -classpath "$WRAPPER_JAR" \
    "-Dmaven.multiModuleProjectDirectory=$DIR" \
    org.apache.maven.wrapper.MavenWrapperMain "$@"
