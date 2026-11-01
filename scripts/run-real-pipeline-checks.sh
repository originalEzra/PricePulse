#!/usr/bin/env bash
set -euo pipefail
pipeline_entry="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$0")/.."
if [[ "${PP_PIPELINE_CHECKS_FROZEN:-false}" != true ]]; then
  mkdir -p target
  pipeline_frozen="$(mktemp "$PWD/target/pipeline-check-entry-XXXXXX")"
  cp "$pipeline_entry" "$pipeline_frozen"
  export PP_PIPELINE_CHECKS_FROZEN=true
  exec bash "$pipeline_frozen"
fi
docker info >/dev/null
mkdir -p target
pipeline_checks="$(mktemp -d "$PWD/target/real-pipeline-checks-XXXXXX")"
python3 - "$PWD" "$pipeline_checks" <<'PY'
from pathlib import Path
import shutil,sys
source,target=map(Path,sys.argv[1:])
for name in ['pom.xml','mvnw']: shutil.copy2(source/name,target/name)
shutil.copytree(source/'.mvn',target/'.mvn')
shutil.copytree(source/'src',target/'src',ignore=shutil.ignore_patterns('application-local.yml','*.key','*.pem'))
PY
cd "$pipeline_checks"
# Exact class allowlist: never select Alpaca*ReadOnlyIT / live-provider tests.
# OpeningGapInfrastructureIT provisions its own MySQL, Redis, NameServer, Broker and topic.
env -u APCA_API_KEY_ID -u APCA_API_SECRET_KEY -u PRICEPULSE_ALPACA_ENABLED \
    -u PRICEPULSE_ALPACA_BUSINESS_WRITES_ENABLED -u ROCKETMQ_NAMESERVER \
    -u PRICEPULSE_AI_ENABLED -u PRICEPULSE_AI_API_KEY -u JWT_SECRET \
  ./mvnw -B -ntp -Pintegration \
    -Dprice-pulse.alpaca.enabled=false -Dprice-pulse.alpaca.business-writes-enabled=false \
    -Dprice-pulse.market.telemetry-enabled=true \
    -Dprice-pulse.assistant.enabled=false -Dprice-pulse.monitoring.enabled=false \
    -Dprice-pulse.monitoring.dynamic-subscriptions-enabled=false \
    '-Dtest=*Test' \
    -Dit.test=MonitorIncidentInfrastructureIT,ReachableOnceInfrastructureIT,InfrastructureIT,MarketTimelineInfrastructureIT,V2IsolationInfrastructureIT,OpeningGapInfrastructureIT,DynamicMonitoringInfrastructureIT,MonitoringInfrastructureIT,V21DeliveryRegressionIT,SimulationOpeningGapInfrastructureIT,SimulationLifecycleInfrastructureIT,OnceOnlyMigrationInfrastructureIT,MonitorIncidentMigrationInfrastructureIT,V2CleanMigrationInfrastructureIT,V2MigrationInfrastructureIT,SubscriptionMigrationInfrastructureIT \
    verify > checks.log 2>&1 || { tail -90 checks.log; exit 1; }
python3 - <<'PY'
from pathlib import Path
import json,xml.etree.ElementTree as E
for kind in ['surefire','failsafe']:
    roots=[E.parse(p).getroot() for p in Path('target',kind+'-reports').glob('TEST-*.xml')]
    totals={key:sum(int(r.get(key,0)) for r in roots) for key in ['tests','failures','errors','skipped']}
    print(kind+': '+json.dumps(totals))
    assert totals['tests']>0 and not totals['failures'] and not totals['errors']
    assert not totals['skipped'], 'Required once-only correctness tests must not be skipped'
print('Isolated checks passed; logs and XML remain under this ignored build directory')
PY
