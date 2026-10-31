#!/usr/bin/env python3
"""Validate and publish only synthetic benchmark artifacts; never copy logs/configuration."""
import argparse
import csv
import gzip
import hashlib
import io
import json
import shutil
from collections import defaultdict
from decimal import Decimal
from pathlib import Path


def ms(value):
    return f"{value / 1_000_000:.3f}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    original = json.loads((args.input / "summary.json").read_text())
    data = json.loads(json.dumps(original))
    # The phase checkpoint is written before maintenance can mutate reporting objects.
    data["replay"] = json.loads((args.input / "replay-summary.json").read_text())
    checkpoint_correction = data["replay"] != original["replay"]
    assert data["environment"]["syntheticOnly"] and not data["environment"]["smoke"]
    assert {row["rules"] for row in data["latency"]} == {1000, 10000, 50000}
    replay = data["replay"]
    assert replay["events"] == 100000 and replay["mismatches"] == 0
    inputs, categories = {}, defaultdict(int)
    previous_end = None
    with gzip.open(args.input / "events.csv.gz", "rt") as handle:
        for row in csv.DictReader(handle):
            number = int(row["event"])
            assert number == len(inputs)
            if previous_end is not None:
                assert Decimal(row["previous"]) == previous_end
            previous_end = Decimal(row["current"])
            assert Decimal("95") <= previous_end <= Decimal("105")
            categories[row["category"]] += 1
            inputs[row["event"]] = row
    assert len(inputs) == 100000 and dict(categories) == replay["categories"]
    latency_groups = defaultdict(list)
    samples = defaultdict(dict)
    with gzip.open(args.input / "latency.csv.gz", "rt") as handle:
        for row in csv.DictReader(handle):
            assert row["equal"] == "true"
            key = (int(row["rules"]), row["density"] + "-" + row["move"])
            samples[(key, row["sample"])][row["scheme"]] = (row["ids_sha256"], row["candidates"])
            latency_groups[(key, int(row["scheme"]))].append(int(row["nanos"]))
    assert all(len(values) == 3 and len(set(values.values())) == 1 for values in samples.values())
    scheme_names = ["MYSQL_FULL_JAVA", "MYSQL_BTREE_IDS", "PRODUCTION_REDIS_IDS"]
    for row in data["latency"]:
        for index, name in enumerate(scheme_names):
            values = sorted(latency_groups[((row["rules"], row["scenario"]), index)])
            assert len(values) == row["samples"]
            for key, percentile in [("p50Ns", .50), ("p95Ns", .95), ("p99Ns", .99)]:
                import math
                assert values[math.ceil(len(values) * percentile) - 1] == row[name][key]
    assert {row["samples"] for row in data["latency"]} == {120}
    assert {row["warmup"] for row in data["latency"]} == {30}
    schemes = {row["scheme"]: row for row in replay["schemes"]}
    observed = defaultdict(lambda: dict(events=0, selects=0, query_ns=0, rows=0, candidates=0, nonempty=0))
    batch = {}
    with gzip.open(args.input / "replay.csv.gz", "rt") as handle:
        for row in csv.DictReader(handle):
            assert row["category"] == inputs[row["event"]]["category"]
            value = observed[row["scheme"]]
            for field, raw in [("selects", "rule_selects"), ("query_ns", "rule_query_ns"), ("rows", "returned_rule_rows"), ("candidates", "candidates")]:
                value[field] += int(row[raw])
            value["events"] += 1
            value["nonempty"] += int(int(row["candidates"]) > 0)
            batch[row["scheme"]] = (row["event"], row["ids_sha256"], row["candidates"])
            if len(batch) == 3:
                assert len(set(batch.values())) == 1
                batch.clear()
    assert not batch
    assert replay["redisQueries"] == observed["REDIS_PREFILTER"]["events"]
    for name, row in schemes.items():
        values = observed[name]
        assert values["events"] == replay["events"]
        assert values["selects"] == row["ruleSelects"] == row["databaseVerifiedRuleSelects"]
        assert values["nonempty"] == row["nonemptyEvents"]
        assert values["candidates"] == row["candidateRows"]
        assert sum(s["rows"] for k, s in row["sql"].items() if k.startswith("RULE")) == values["rows"]
        assert sum(s["nanos"] for k, s in row["sql"].items() if k.startswith("RULE")) == values["query_ns"]
        assert row["sql"]["STOCK_LOCK_SELECT"]["executions"] == replay["events"]
        assert row["sql"]["WATERMARK_SELECT"]["executions"] == replay["events"]
        assert row["sql"]["STOCK_UPDATE"]["executions"] == replay["events"]
        assert row["sql"]["WATERMARK_UPDATE"]["executions"] == replay["events"]
    archived = args.input / "benchmark-source-at-run.java.txt"
    for path, digest in data["sourceSha256"].items():
        source = Path(path)
        if source.name == "RuleSelectionExperiment.java" and archived.exists():
            source = archived
        assert hashlib.sha256(source.read_bytes()).hexdigest() == digest, path
    args.output.mkdir(parents=True, exist_ok=True)
    allowed = ["latency.csv.gz", "replay.csv.gz", "events.csv.gz", "maintenance.json", "replay-summary.json",
               "rules-1000.csv.gz", "rules-10000.csv.gz", "rules-50000.csv.gz"]
    for name in allowed:
        shutil.copyfile(args.input / name, args.output / name)
    shutil.copyfile(args.input / "summary.json", args.output / "summary-original.json")
    if archived.exists():
        shutil.copyfile(archived, args.output / archived.name)
    (args.output / "summary.json").write_text(json.dumps(data, indent=2) + "\n")
    explain = "\n\n".join(f"## {row['rules']} / {row['scenario']}\nID covering query:\n{row['idPlan']}\nHydration query:\n{row['hydrationPlan']}" for row in data["plans"])
    (args.output / "explain-analyze.txt").write_text(explain.rstrip() + "\n")
    with (args.output / "latency-summary.csv").open("w", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["rules", "scenario", "scheme", "average_candidates", "p50_ms", "p95_ms", "p99_ms", "mean_ms"])
        for row in data["latency"]:
            for name in ["MYSQL_FULL_JAVA", "MYSQL_BTREE_IDS", "PRODUCTION_REDIS_IDS"]:
                stat = row[name]
                writer.writerow([row["rules"], row["scenario"], name, row["averageCandidates"], *[ms(stat[key]) for key in ["p50Ns", "p95Ns", "p99Ns", "meanNs"]]])
    with (args.output / "rule-select-qps-10s.csv").open("w", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["scheme", "window_start_seconds", "window_seconds", "actual_rule_selects", "qps"])
        for name, bins in replay["sqlTenSecondBins"].items():
            for number, count in sorted(bins.items(), key=lambda item: int(item[0])):
                seconds = min(10, replay["commonWindowSeconds"] - int(number) * 10)
                writer.writerow([name, int(number) * 10, seconds, count, count / seconds])
    direct, redis, fast = [schemes[name] for name in ["MYSQL_DIRECT", "REDIS_PREFILTER", "MYSQL_EQUAL_PRICE_FASTPATH"]]
    selection_delta = (redis["selectorTotalNs"] - direct["selectorTotalNs"]) / 1e9
    direct_rows = observed["MYSQL_DIRECT"]["rows"]
    for name, alias in [("MYSQL_DIRECT", "direct_rules"), ("REDIS_PREFILTER", "prefiltered_rules"), ("MYSQL_EQUAL_PRICE_FASTPATH", "fast_rules")]:
        server_rows = sum(item["returnedRows"] for item in replay["databaseDigestDeltas"] if "`" + alias + "`" in item["sql"])
        assert server_rows == observed[name]["rows"]
    reduction = (1 - redis["ruleSelects"] / direct["ruleSelects"]) * 100
    fast_reduction = (1 - redis["ruleSelects"] / fast["ruleSelects"]) * 100
    wins = sum(row["PRODUCTION_REDIS_IDS"]["p95Ns"] < row["MYSQL_BTREE_IDS"]["p95Ns"] for row in data["latency"])
    sparse = next(row for row in data["latency"] if row["rules"] == 10000 and row["scenario"] == "sparse-UP")
    lines = ["# V2.1：MySQL B+ 树与 Redis 派生候选索引对照", "",
             "实验日期：2026-10-31（Australia/Sydney）。只运行隔离、合成夹具；业务源码未改，未迁移、重启或故障注入正式环境。", "",
             "## 先看结论", "",
             f"- Redis 热候选 P95 在 18 个规模／密度／方向场景中的 **{wins} 个**低于合理复合索引 MySQL；全部场景都在下表，不预设 Redis 总是更快。",
             f"- 固定 100000 笔中 **{redis['nonemptyEvents']} 笔（{redis['nonemptyRatio']:.2%}）**有候选。相对逐笔 MySQL，规则 SELECT 从 {direct['ruleSelects']} 降至 {redis['ruleSelects']}，减少 **{reduction:.2f}%**。",
             f"- 同价直接返回空集合的 MySQL 对照仅需 {fast['ruleSelects']} 次；Redis 相对它只再减少 **{fast_reduction:.2f}%**。不能把同价短路也归为 Redis 独有收益。",
             f"- 选择层累计耗时：逐笔 MySQL {direct['selectorTotalNs']/1e9:.3f}s、Redis＋MySQL 候选回查 {redis['selectorTotalNs']/1e9:.3f}s、MySQL 同价短路 {fast['selectorTotalNs']/1e9:.3f}s。这是选择层，不是正式行情或通知端到端延迟。", "",
             "## 环境与方法", "",
             f"{data['environment']['os']}／{data['environment']['arch']}，Java {data['environment']['java']}，实验 JVM 可见 {data['environment']['visibleProcessors']} CPU、最大堆 {data['environment']['maxHeapBytes']/1024/1024:.0f} MiB。MySQL {data['environment']['mysql']}，JDBC {data['jdbcDriver']}，Redis 7.4.6；两容器各限 1 CPU／768 MiB、仅回环随机端口、MySQL buffer pool 128 MiB。与正式服务共享物理主机，未做机器独占或噪声置信区间分析。", "",
             "- 只创建 `bench_*` 实验表，无 Spring 应用、Flyway、用户规则、生产备份、供应商或 MQ 连接。MySQL 保留默认持久提交策略，Redis 作为派生缓存关闭持久化。",
             "- 规则生成 seed 为 20261031＋规则数；每规模三方案使用完全相同的数据。ACTIVE／COOLDOWN 各 50%，各状态 ABOVE／BELOW 各半；阈值在 90–110 均匀生成至四位小数，重新激活价相对阈值偏移 0.05–0.75。输入 CSV 全部公开，未按测试结果调整分布。",
             "- A 从 MySQL 读取该股全部有效规则并转 Java 对象筛选；B 用两个复合 B+ 树做 disjoint UNION ALL，热候选阶段仅返回 ID；C 直接调用生产 `RuleIndex.candidates → ThresholdIndex`，包含 token 检查、Lua、价格编码和生产指标计时，没有替换简化算法。",
             "- 实验 JDBC 适配器负责数据加载／SQL 精确计数，没有模拟 JDBC 或 Redis 返回值。候选层三组最终都返回 ID；回放中直接 SQL 与 Redis 回查均加载同一组规则字段并转换同类 Java 对象。",
             "- 每场景先预热 30 轮，再测 120 轮，六种执行排列轮换；网络往返、结果读取和 Java 转换在计时内，集合排序／SHA 校验在查询计时外。每次核对 ID 集合与重复 ID，均为零差异。单 JVM 顺序实验，P99 为 nearest-rank 小样本估计。", "",
             "## 热候选查询延迟", "", "单位 ms；每格 P50／P95／P99。UP 同时覆盖 ACTIVE ABOVE／COOLDOWN BELOW，DOWN 覆盖 ACTIVE BELOW／COOLDOWN ABOVE。稀疏 0.01、中等 1.00、密集 8.00 的价格区间均以 100 为前值。", "",
             "| 规则数 | 场景 | 候选数 | A 全读＋Java | B MySQL B+ | C 生产 Redis |", "|---:|---|---:|---:|---:|---:|"]
    for row in sorted(data["latency"], key=lambda row: (row["rules"], row["scenario"])):
        cells = ["／".join(ms(row[name][key]) for key in ["p50Ns", "p95Ns", "p99Ns"]) for name in ["MYSQL_FULL_JAVA", "MYSQL_BTREE_IDS", "PRODUCTION_REDIS_IDS"]]
        lines.append(f"| {row['rules']} | {row['scenario']} | {row['averageCandidates']:.0f} | {' | '.join(cells)} |")
    lines += ["", "[原始延迟样本](latency.csv.gz) · [含均值的 CSV](latency-summary.csv) · [实际 EXPLAIN ANALYZE](explain-analyze.txt) · [实验索引 DDL](../../../scripts/performance/rule-selection-indexes.sql)", "",
              "MySQL 分别建立 `(stock_id,status,direction,threshold_price)`、`(stock_id,status,direction,rearm_price)`。二级索引隐含主键 ID，因此 ID 查询可覆盖；完整规则回查另计。没有 FORCE INDEX。计划记录同时保留覆盖查询与规则对象查询的实际时间、行数和访问路径，不将估计行数冒充访问行数。", "",
              "## 100000 笔固定回放与规则 SELECT", "",
              "类别洗牌 seed 20261031、价格步进 seed 20261032；20% 同价、60% ±0.01–0.05、15% ±0.06–0.20、5% ±0.21–1.00，打乱类别后生成一条 95–105 边界反射的连续价格路径。阈值分布未根据路径调参。这是可复现的价格风格模型，不是从真实成交拟合的频率。", "",
              "规则状态冻结：只比较候选选择，不执行触发、冷却转移或通知；不能将候选事件比例当作正式提醒触发率。三组每笔轮换执行，使用相同路径与独立的持久状态控制行。", "",
              "**没有省略数据库前置成本：** 每笔每方案都实际开启事务、股票控制行 `FOR UPDATE`、读取持久水位、校验前价／序号、更新价格与水位并提交；各有 100000 次股票锁 SELECT、100000 次水位 SELECT、200000 次控制 UPDATE。连接的事务设置与提交同样发生。它们是实验控制表上的对应职责，不冒充运行了生产 `QuoteIngestionService`；正式报价事件、真实规则状态转移、触发和 Outbox 写入不在本回放范围。", "",
              "| 方案 | 规则 SELECT（客户端＝数据库） | 规则查询累计秒 | 返回规则行 | 共享窗口规则 QPS | 选择层累计秒 | 含前置控制累计秒 |", "|---|---:|---:|---:|---:|---:|---:|"]
    for name, row in schemes.items():
        value = observed[name]
        lines.append(f"| {name} | {row['ruleSelects']}＝{row['databaseVerifiedRuleSelects']} | {value['query_ns']/1e9:.3f} | {value['rows']} | {row['ruleSelectsPerCommonWindowSecond']:.2f} | {row['selectorTotalNs']/1e9:.3f} | {row['guardedTotalNs']/1e9:.3f} |")
    lines += ["", f"统一观测窗口 {replay['windowStartUtc']}–{replay['windowEndUtc']}，单调时钟 {replay['commonWindowSeconds']:.3f}s。QPS 分母是三方案轮换的同一个真实窗口，包含验证和 CSV 写入等待，**不是单方案最大 QPS或外部施压结果**。另提供 [10 秒实际计数窗口](rule-select-qps-10s.csv)。Redis 逻辑候选调用 {replay['redisQueries']} 次（逐笔 CSV 核验，非线缆命令计数），完整隔离回放重建 {replay['redisRebuilds']} 次；三组集合差异 0。", "",
              f"表中规则查询累计秒为客户端 executeQuery 到规则对象读取完成的墙钟耗时，含网络和 Java 转换；服务端 SUM_TIMER_WAIT 另存于 digest，也不是 CPU 时间。本次三组返回规则行均为 **{direct_rows}**，服务端已访问行分项见 digest，不能仅凭 SQL 次数下降声称行读取同比下降。", "",
              "规则 SQL 在 executeQuery→完整读取／对象转换处精确埋点；以不同 SQL 表别名在隔离 `performance_schema.events_statements_summary_by_digest` 对 COUNT_STAR 前后差分交叉验证，查询次数逐组一致。服务端返回行／访问行／计时也在 summary 原始 digest 中；数据库诊断和准备查询不计入规则选择窗口。没有用代码分支估算 SQL 次数。", "",
              f"加上已实际计数的两类前置 SELECT，逐笔 MySQL／Redis／同价短路三组分别为 {direct['ruleSelects']+2*replay['events']}／{redis['ruleSelects']+2*replay['events']}／{fast['ruleSelects']+2*replay['events']} 次 SELECT。此口径只覆盖实验已列出的 SELECT，不含事务设置、提交和正式报价／Outbox 等操作，不能将规则 SELECT 降幅写成全部数据库命令降幅。", "",
              "[固定价格序列](events.csv.gz) · [逐笔三方案原始记录](replay.csv.gz) · [完整环境、计数与 digest](summary.json)", "",
              "## 索引构建、内存和故障恢复", "", "热查询不含构建。Redis 冷重建含 MySQL 全读、Java 编码、Lua 重建与查询重试；每规模独立三次，原始值均保留。下表展示首次该规模的构建／存储记录，完整重复记录在 summary。", "",
              "| 规则数 | MySQL 两索引构建 ms | Redis 冷重建 ms（三次） | Redis key 字节 | MySQL 数据／索引字节 |", "|---:|---:|---|---:|---|"]
    for size in [1000, 10000, 50000]:
        values = [row for row in data["builds"] if row["rules"] == size]
        indexes = [row["nanos"] for row in values if row["kind"].startswith("MYSQL_INDEX")][:2]
        cold = [row["nanos"] for row in values if row["kind"] == "REDIS_COLD_WITH_DB_LOAD"][:3]
        storage = next(row for row in values if row["kind"] == "STORAGE")
        mysql = storage["mysql"][0]
        lines.append(f"| {size} | {ms(sum(indexes))} | {'／'.join(ms(n) for n in cold)} | {storage['redisKeyBytes']} | {mysql['DATA_LENGTH']}／{mysql['INDEX_LENGTH']} |")
    lines += ["", "存储数仅针对实验规则的六字段模型和一个 ZSet key（含 token），不包括完整生产规则文本、JVM／连接池或 Redis 全实例开销；不能相互相减推导整套系统内存收益。", "",
              "维护过程复用生产 `RuleIndex.changed`／Lua 和 token MISS 重建。单写者按序新增、暂停、删除、删缓存 key、数据库事务回滚造成 token 不一致、再次热查询；没有进行生产故障注入。", "",
              "| 场景 | 耗时 ms | 重建 | 规则 SELECT（含独立核验） | 集合一致 |", "|---|---:|---:|---:|---|"]
    for row in data["maintenance"]["steps"]:
        lines.append(f"| {row['action']} | {ms(row['elapsedNs'])} | {row['rebuilds']} | {row['allRuleSelectsIncludingVerification']} | {row['equal']} |")
    lines += ["", "共用 token 读取、规则变更 DML 和索引 token DML 在 [维护原始记录](maintenance.json) 分项保存。新增／暂停／删除各一次增量缓存更新；缺失及回滚各触发一次全读重建，热恢复无重建。耗时包含场景操作及 MySQL 独立核验，不应当成单次 Lua 写延迟；这是小规模恢复机制验证，没有测长期规则高频变更或多写者竞争。", "",
              "## 成本与架构取舍", "",
              "1. **延迟优势由规模和密度决定。** 全量扫描对照不是优化后的 MySQL；合理 B+ 树已经避免读整股规则。应比较上面 B／C 的同场景结果，不能沿用旧报告的 30 倍扫描对照当成 B+ 树优势。",
              f"2. **规则 SELECT 减少 {direct['ruleSelects']-redis['ruleSelects']} 次，不等于少读同样数量的规则行。** 非空时两方案返回相同候选行；空查询减少可降低解析／索引探测／往返，但没有磁盘物理 I/O 或正式服务器 CPU 对照，不能量化磁盘压力收益。同价 SQL 优化后的额外减少仅 {fast['ruleSelects']-redis['ruleSelects']} 次。",
              "3. **MySQL 仍然必需。** 股票行锁、事件去重、正式报价和水位、权威规则／版本再校验、状态转移、触发快照与 Outbox 事务均不能由 Redis 代替；恢复基线／正式开盘路径还会读取 COOLDOWN 规则，本实验只覆盖正常候选路径。本实验未删除这些生产步骤，也不宣称实盘总查询同比例下降。",
              "4. **Redis 有额外成本。** 常态每笔一次网络查询，非空再多一次／多批 MySQL 回查；变更需双系统 token／索引维护，失效需全量数据库加载并占用 Lua／网络／内存。当前低规则数或高候选密度、频繁变更、MySQL 已有合适索引时，直接 SQL 更简单且可能更快。",
              "5. **当前架构的理由与限制。** PricePulse 采用派生候选索引，是为了实现内存区间预筛选、共享真实／模拟候选机制及可验证重建；它是可重建的加速层，MySQL 才是事实源。这是工程设计取舍，不代表当前十五股的实际规则量必须用 Redis，也不以本机微基准为架构普适证明。本轮不更换现有业务算法。", "",
              f"**本负载的判断：** {redis['nonemptyRatio']:.2%} 事件非空，Redis＋回查比逐笔 SQL {'多' if selection_delta>0 else '少'} {abs(selection_delta):.3f}s 选择耗时。{'在本次资源和负载下，直接 MySQL 的选择层表现更好。' if selection_delta>0 else '本次选择层观察到收益，仍需另计维护与实际业务成本。'}故障恢复的额外全读和 Redis 内存见上表；注入频率人为设定，不能摊销成真实频率成本或据此宣称维护一定值得。", "",
              "## 可用于简历的表述", "",
              f"> 实现 Redis ZSet＋Lua 区间候选索引与 token 一致性重建，完成 1000–50000 条规则的三方案对照及 10 万笔固定回放，候选集合零差异；相对逐笔 MySQL 范围查询，规则 SELECT 减少 {reduction:.2f}%（同价 SQL 短路后额外减少 {fast_reduction:.2f}%），明确保留 MySQL 事务、行锁及权威状态校验。", "",
              "该表述不宣称吞吐提升或完整处理延迟下降。面试应同时说明 SQL 优化对照、非空回查的双跳成本和维护代价。", "",
              "## 复现与原始数据", "", "```bash", "bash scripts/run-rule-selection-experiment.sh --output=target/rule-selection-new-run", "python3 scripts/performance/render-rule-selection-report.py \\", "  --input target/rule-selection-new-run \\", "  --output docs/performance/rule-selection-new-run", "```", "",
              "小样本只校验逻辑：`--smoke --output=target/rule-selection-smoke-new`，不可作为上面的全规模性能结论。脚本自动关闭自己的容器，不运行原 `run-performance.sh`（后者会启动正式 Compose MQ）。", "",
              "输入规则：[1000](rules-1000.csv.gz)／[10000](rules-10000.csv.gz)／[50000](rules-50000.csv.gz)。全部输入为合成，原始延迟／回放 CSV 为 gzip，解压后是 UTF-8 CSV。源代码指纹、环境和每组数据库计数保留在 summary，文件摘要见 [manifest](manifest.json)。"]
    if checkpoint_correction:
        lines += ["", "## 原始汇总的计数快照修正", "",
                  "首次完整实验的 summary 引用了可变 SQL 统计对象，后续维护准备阶段向同一对象追加了计数。实时写出的 replay-summary.json 和逐笔 CSV 不受影响。本报告与公开 summary 采用该阶段不可变快照，逐笔累计、客户端及数据库 digest 三方核验一致；原 summary 完整保留为 summary-original.json，没有删除失败／异常记录。",
                  "实验源代码仅在回放测量结束后的汇总处改为深拷贝，防止未来重现；未改数据、查询、执行顺序或计时区间，并另用小样本验证。全规模测量时源码留存 benchmark-source-at-run.java.txt，SHA 与源报告一致。这里不是为新代码重新宣称一次完整性能运行。"]
    (args.output / "report.md").write_text("\n".join(lines) + "\n")
    manifest = {path.name: {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in sorted(args.output.iterdir()) if path.is_file() and path.name != "manifest.json"}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"validated": True, "latencyTriples": len(samples), "replayEvents": replay["events"], "ruleSelectReductionPct": reduction, "additionalReductionVsFastpathPct": fast_reduction, "redisP95Wins": wins, "output": str(args.output)}))


if __name__ == "__main__":
    main()
