#!/usr/bin/env bash
set -euo pipefail

# Build linux-x64 self-contained single-file binary for CSharpCodeParser
# Requires: dotnet SDK (7/8/9). We'll attempt dockerized build if dotnet not present.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RUNTIME="linux-x64"
OUT_DIR="$SCRIPT_DIR/$RUNTIME"
BIN_NAME="CSharpCodeParser"

build_with_dotnet() {
  dotnet restore "$SCRIPT_DIR/CSharpCodeParser.csproj"
  dotnet publish "$SCRIPT_DIR/CSharpCodeParser.csproj" -c Release -r "$RUNTIME" \
    --self-contained true /p:PublishSingleFile=true /p:DebugType=None /p:PublishTrimmed=false \
    -o "$OUT_DIR"
}

USE_DOCKER=false
if command -v dotnet >/dev/null 2>&1; then
  DOTNET_VER=$(dotnet --version | cut -d'.' -f1)
  if [[ "$DOTNET_VER" =~ ^[0-9]+$ ]] && [[ "$DOTNET_VER" -ge 9 ]]; then
    echo "==> Building with local dotnet SDK ($DOTNET_VER)"
    if ! build_with_dotnet; then
      USE_DOCKER=true
    fi
  else
    echo "==> Local dotnet SDK ($DOTNET_VER) is < 9; will use Docker"
    USE_DOCKER=true
  fi
else
  echo "==> dotnet not found; will use Docker"
  USE_DOCKER=true
fi

if [ "$USE_DOCKER" = true ]; then
  echo "==> Building via Docker mcr.microsoft.com/dotnet/sdk:9.0"
  docker run --rm -u $(id -u):$(id -g) -v "$SCRIPT_DIR":"/src" -w "/src" \
    mcr.microsoft.com/dotnet/sdk:9.0 bash -lc '
      dotnet restore /src/CSharpCodeParser.csproj && \
      dotnet publish /src/CSharpCodeParser.csproj -c Release -r linux-x64 --self-contained true /p:PublishSingleFile=true -o /src/linux-x64
    '
fi

chmod 755 "$OUT_DIR/$BIN_NAME" || true
echo "==> Built $OUT_DIR/$BIN_NAME"


