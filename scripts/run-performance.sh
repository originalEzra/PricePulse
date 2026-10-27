#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
docker info >/dev/null
docker compose up -d namesrv broker
./mvnw -B -ntp -Pperformance verify "$@"
