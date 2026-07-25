#!/usr/bin/env bash
set -euo pipefail

# Regenerates the committed example outputs in examples/.
# Run this after changing a parser's output, then commit the result: the diff
# shows reviewers exactly how the schema changed.
#
# Requires both parsers to be built first:
#   (cd java && ./build.sh) && (cd csharp && ./build.sh)
#
# The flags here must match the ones in .github/workflows/ci.yml, which diffs
# live output against these files.

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

for name in OrderService Nesting; do
  echo "==> examples/$name.java.output.json"
  cat "examples/$name.java" \
    | java -jar "$JAR" --pretty --file "examples/$name.java" > "examples/$name.java.output.json"

  echo "==> examples/$name.cs.output.json"
  cat "examples/$name.cs" \
    | "$CSBIN" --pretty --file "examples/$name.cs" > "examples/$name.cs.output.json"
done

echo "==> Done. Review the diff with: git diff examples/"
