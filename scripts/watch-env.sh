#!/bin/sh
# Source this only from project scripts; exports affect that process, never a profile.
WATCH_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export IDF_PATH="$WATCH_ROOT/.tools/esp-idf"
export IDF_TOOLS_PATH="$WATCH_ROOT/.tools/idf-tools"
export IDF_PYTHON_ENV_PATH="$WATCH_ROOT/.tools/idf-python"
export IDF_COMPONENT_CACHE_PATH="$WATCH_ROOT/.cache/idf-components"
export XDG_CACHE_HOME="$WATCH_ROOT/.cache"
export CCACHE_DIR="$WATCH_ROOT/.cache/ccache"
export PATH="$IDF_PYTHON_ENV_PATH/bin:$PATH"
