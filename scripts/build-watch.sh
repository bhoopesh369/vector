#!/bin/bash
set -eo pipefail
cd "$(dirname "$0")/.."
source scripts/watch-env.sh
export PIP_CACHE_DIR="$WATCH_ROOT/.cache/pip"
source "$IDF_PATH/export.sh" > "$WATCH_ROOT/.cache/idf-export.log" 2>&1
python scripts/prepare-watch.py
exec python "$IDF_PATH/tools/idf.py" -C .cache/watch-project -B "$WATCH_ROOT/build/watch-factory" build
