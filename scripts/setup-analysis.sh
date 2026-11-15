#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .tools .cache/pip .cache/matplotlib
if [ ! -x .tools/imu-analysis/bin/python ]; then
  python3 -m venv .tools/imu-analysis
fi
PIP_CACHE_DIR="$PWD/.cache/pip" .tools/imu-analysis/bin/python -m pip install -r requirements-analysis.txt
