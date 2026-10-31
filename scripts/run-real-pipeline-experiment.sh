#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker info >/dev/null
# Build a source-only mirror: never overwrite target/classes used by the live backend.
# No local profile, private resource, Spring application, external feed or formal MQ is loaded.
pipeline_root="$PWD"
mkdir -p target
pipeline_build="$(mktemp -d "$PWD/target/real-pipeline-build-XXXXXX")"
python3 - "$pipeline_root" "$pipeline_build" <<'PY'
from pathlib import Path
import shutil, sys
source, target = map(Path,sys.argv[1:])
for name in ['pom.xml','mvnw']:
    shutil.copy2(source/name,target/name)
shutil.copytree(source/'.mvn',target/'.mvn')
shutil.copytree(source/'src',target/'src',ignore=shutil.ignore_patterns('application-local.yml','*.key','*.pem'))
PY
(
  cd "$pipeline_build"
  ./mvnw -B -ntp -DskipTests test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/pipeline-classpath.txt > build.log 2>&1 || { tail -70 build.log; exit 1; }
  if [[ "${PP_PIPELINE_UNIT_TESTS:-false}" == true ]]; then
    ./mvnw -B -ntp test > unit-tests.log 2>&1 || { tail -70 unit-tests.log; exit 1; }
  fi
)
export PP_PIPELINE_COMMIT="$(git rev-parse HEAD)"
export PP_PIPELINE_SOURCE_ROOT="$pipeline_build"
exec nice -n 10 java -Xms256m -Xmx768m -XX:ActiveProcessorCount=2 \
  -cp "$pipeline_build/target/test-classes:$pipeline_build/target/classes:$(cat "$pipeline_build/target/pipeline-classpath.txt")" \
  com.ezra.pricepulse.market.RealPipelineExperiment "$@"
