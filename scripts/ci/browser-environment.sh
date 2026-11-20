#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$HOME"
npm ci --cache /tmp/ci-npm-cache
node scripts/ci/browser-runtime.cjs
