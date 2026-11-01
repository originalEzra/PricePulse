#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# These experiments measure the retired REPEAT/COOLDOWN model. Do not reuse their results for V12.
if [[ ! -f "src/test/java/com/ezra/pricepulse/performance/RuleSelectionExperiment.java" ]]; then
  echo 'Historical performance harness: reproduce from local commit c99377c in a separate checkout. See docs/once-only-model/acceptance.md.' >&2
  exit 2
fi
# No Spring context, local profile, production credentials, Flyway or provider socket.
# Both stores are disposable Testcontainers, each bounded to 1 CPU / 768 MiB.
docker info >/dev/null
./mvnw -B -ntp -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/rule-selection-classpath.txt
export PP_BENCH_COMMIT="$(git rev-parse HEAD)"
exec nice -n 10 java -Xms256m -Xmx768m -XX:ActiveProcessorCount=2 \
  -cp "target/test-classes:target/classes:$(cat target/rule-selection-classpath.txt)" \
  com.ezra.pricepulse.performance.RuleSelectionExperiment "$@"
