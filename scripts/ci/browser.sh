#!/usr/bin/env bash
# Runs only inside the digest-pinned, locked-version Playwright image.
set -euo pipefail
bash scripts/ci/browser-environment.sh
npm test
