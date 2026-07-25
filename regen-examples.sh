#!/usr/bin/env bash
set -euo pipefail

# Regenerates the committed example outputs in examples/.
# Run this after changing a parser's output, then commit the result: the diff
# shows reviewers exactly how the schema changed.
#
# Requires both parsers to be built first:
#   (cd java && ./build.sh) && (cd csharp && ./build.sh)

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAR="java/JavaCodeParser_Full.jar"
CSBIN="csharp/linux-x64/CSharpCodeParser"

if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR - run ./build.sh in java/ first" >&2
  exit 1
fi

if [[ ! -x "$CSBIN" ]]; then
  echo "Missing $CSBIN - run ./build.sh in csharp/ first" >&2
  exit 1
fi

echo "==> examples/OrderService.java.output.json"
cat examples/OrderService.java | java -jar "$JAR" > examples/OrderService.java.output.json

echo "==> examples/OrderService.cs.output.json"
cat examples/OrderService.cs | "$CSBIN" > examples/OrderService.cs.output.json

echo "==> examples/Nesting.java.output.json"
cat examples/Nesting.java | java -jar "$JAR" > examples/Nesting.java.output.json

echo "==> examples/Nesting.cs.output.json"
cat examples/Nesting.cs | "$CSBIN" > examples/Nesting.cs.output.json

echo "==> Done. Review the diff with: git diff examples/"
