#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
# These experiments measure the retired REPEAT/COOLDOWN model. Do not reuse their results for V12.
if [[ ! -f "src/test/java/com/ezra/pricepulse/performance/PerformanceIT.java" ]]; then
  echo 'Historical performance harness: reproduce from local commit c99377c in a separate checkout. See docs/once-only-model/acceptance.md.' >&2
  exit 2
fi
docker info >/dev/null
docker compose up -d namesrv broker
./mvnw -B -ntp -Pperformance verify "$@"
