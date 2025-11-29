#!/usr/bin/env bash
set -euo pipefail

# create_tree.sh
# Create the directory tree for the Self-Hypnosis App project.
# Usage: ./create_tree.sh [ROOT_DIR]
# If ROOT_DIR is omitted, the script creates a `selfhypnosis-app` folder in the current directory.

ROOT_DIR="${1:-selfhypnosis-app}"

dirs=(
  "$ROOT_DIR/src/templates"
  "$ROOT_DIR/src/frequency"
  "$ROOT_DIR/src/narration"
  "$ROOT_DIR/src/player"
  "$ROOT_DIR/src/tracking"

  "$ROOT_DIR/assets/tones"
  "$ROOT_DIR/assets/ambient"
  "$ROOT_DIR/assets/voices"

  "$ROOT_DIR/templates"
  "$ROOT_DIR/scripts"
  "$ROOT_DIR/tests"
  "$ROOT_DIR/docs"
  "$ROOT_DIR/build"
)

for d in "${dirs[@]}"; do
  mkdir -p "$d"
done

echo "Directory tree created under: $ROOT_DIR"

exit 0
