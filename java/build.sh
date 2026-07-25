#!/usr/bin/env bash
set -euo pipefail

# Build a fat JAR: JavaCodeParser_Full.jar
# Requirements:
# - JDK (javac, jar) installed
# - Internet access not required if lib/*.jar already present
# Produces:
# - JavaCodeParser_Full.jar in current directory

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MAIN_CLASS="JavaCodeParser"
OUTPUT_JAR="JavaCodeParser_Full.jar"
MANIFEST_FILE="MANIFEST.MF"

MAVEN="https://repo1.maven.org/maven2"
DEPS=(
  "com/github/javaparser/javaparser-core/3.26.2/javaparser-core-3.26.2.jar"
  "com/github/javaparser/javaparser-symbol-solver-core/3.26.2/javaparser-symbol-solver-core-3.26.2.jar"
  "com/google/code/gson/gson/2.11.0/gson-2.11.0.jar"
  "com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar"
)

echo "==> Resolving dependencies into lib/"
mkdir -p lib
for DEP in "${DEPS[@]}"; do
  JAR="lib/$(basename "$DEP")"
  if [[ ! -f "$JAR" ]]; then
    echo "   downloading $(basename "$DEP")"
    curl -fsSL -o "$JAR" "$MAVEN/$DEP"
  fi
done

echo "==> Cleaning temp build directory"
rm -rf build_tmp bin
mkdir -p bin

echo "==> Compiling sources to bin/"
javac -encoding UTF-8 -d bin -cp "lib/*" src/JavaCodeParser.java

echo "==> Creating build staging directory"
mkdir -p build_tmp

echo "==> Copying compiled classes"
cp -R bin/* build_tmp/

if compgen -G "lib/*.jar" > /dev/null; then
  echo "==> Unpacking dependency jars into build_tmp"
  (cd build_tmp && for J in ../lib/*.jar; do echo "   extracting $(basename "$J")"; jar xf "$J"; done)
else
  echo "==> No lib/*.jar dependencies found"
fi

if [[ ! -f "$MANIFEST_FILE" ]]; then
  echo "Main-Class: $MAIN_CLASS" > "$MANIFEST_FILE"
fi

echo "==> Creating $OUTPUT_JAR"
jar cvfm "$OUTPUT_JAR" "$MANIFEST_FILE" -C build_tmp . >/dev/null

echo "==> Done: $OUTPUT_JAR"

