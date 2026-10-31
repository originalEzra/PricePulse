# 成交去重：INSERT 优先实验

本目录独立于此前 Outbox／队列基线。仅比较同一条 REAL 事务路径中的：

- A：`30df066` 的前置 `findByEventKey`＋INSERT。
- B：`2e3112a` 先 INSERT；仅在 MySQL 1062／SQLState 23000 且错误尾部明确为 `quote_events.uk_quote_events_event_key` 时，查询并复用原来的完整内容校验。

**结论：不保留 B，最终工作树恢复 A。** 正常 SQL 7→6 已实测，但关键少量命中和异常路径的延迟／排队未满足保留门槛。见 [完整报告](report.md)、[全部五组对照](tables.md)和[机器可读决定](decision.json)。

股票级 FOR UPDATE、READ_COMMITTED、RECEIVED／PROCESSED／DISCARDED、成交身份／纳秒水位、规则状态机、Redis、Outbox 和消息语义不变。唯一索引保留，不引入 INSERT IGNORE／UPSERT、重试框架或微批。

所有运行均使用既有脚本新建的限额 MySQL／Redis、合成用户／成交。没有正式数据操作、服务重启、额外 IEX 或自动加载。原始逐笔 CSV.gz／运行日志在忽略的 target；仓库只保存汇总和文件摘要。

## 正确性优先

先运行新增单元、真实 MyBatis／Spring 事务专项和现有相关隔离回归，再开始计分。底层无股票锁的 JDBC／Mapper 测试仅研究 InnoDB 唯一约束等待及死锁；它不是生产入口，服务测试始终保留股票锁。

官方依据与实际边界：

- MySQL 说明普通重复键错误回滚失败语句；死锁可回滚整个事务，二者不能混同。[InnoDB 错误处理](https://dev.mysql.com/doc/refman/8.4/en/innodb-error-handling.html)
- 重复键检查可能持有共享记录锁，多个等待者在持有者回滚后可能形成死锁。[InnoDB 锁规则](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html)
- Spring 在异常越过事务拦截边界时决定回滚；MyBatis-Spring 的 SqlSessionTemplate 参加当前事务并翻译异常。是否能捕获后继续，仍以项目实际版本集成测试为准。[Spring 回滚](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)、[MyBatis SqlSessionTemplate](https://mybatis.org/spring/sqlsession.html)

B 只承认当前实测服务器的完整表名／约束名格式；缺少 JDBC 信息、未知语言／驱动格式、其他表同名约束、其他索引、死锁或连接错误均继续抛出。没有依赖异常值里的任意子串，也不记录完整错误消息。

## 测量方案

相同固定 seed `2026103101`、每股 1000 条规则（零规则除外）、单股单线程／1024、原持久提交参数和资源限额。A、B 各五个独立 JVM／容器运行，按 A→B→A→B 交替；每轮 16 档，每档热身 2s、正式 8s：

| 场景 | 输入速率／s | 说明 |
| --- | --- | --- |
| ZERO_RULES | 50／100／150／200 | 没有规则 |
| ZERO_HIT | 同上 | 1000 条 ACTIVE／COOLDOWN，价格不触及阈值 |
| FEW_HIT | 同上 | 两条近价 REPEAT，背景阈值不命中 |
| MIXED_DUPLICATES | 同上 | 相同少量命中配置，每 20 个输入中一个复制前笔事件内容；任务与接收时间独立，不覆盖原样本 |

另在每轮独立测量三条直接事务调用路径，**不使用队列、不冒充单股持续输入能力**：已有重复 200 次、内容冲突 200 次、100 对并发同键请求。前两条各热身 50 次，并发路径热身 20 对。并发服务调用仍使用股票行锁；每对必须一个 PROCESSED、一个 DUPLICATE。

每次真实 JDBC execute 调用记数，包含失败 INSERT；事务计时位于代理外，含提交／回滚完成。正常路径预计 7→6 次业务 SQL；重复路径预计 2→3。实际值以采集为准，不计入 JDBC 事务控制、采样查询等其他数据库访问。

8s 窗口用于处理耗时及积压对照；不能据此宣称长期持续吞吐能力。分开报告 PROCESSED／全部已处理请求，避免将预期重复事件误当失败或积压。

## 复现入口

从仓库根目录运行，候选使用不可变提交，不用工作区临时内容：

```bash
PP_PIPELINE_INGESTION_REF=2e3112a PP_PIPELINE_UNIT_TESTS=true bash scripts/run-real-pipeline-experiment.sh \
  --suite=dedup-correctness --output=target/dedup-correctness-repro
bash scripts/run-real-pipeline-checks.sh

python3 scripts/run-insert-first-comparison.py \
  --before=30df066 --after=2e3112a --pairs=5 --seconds=8 --warmup-seconds=2 \
  --output=target/dedup-ab-repro

python3 scripts/performance/report-insert-first.py \
  --input=target/dedup-ab-repro --output=target/dedup-report-repro
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s scripts/tests -p 'test*report.py'
```

运行器不 checkout 工作区，只在私有编译镜像替换 QuoteIngestionService；每轮记录实际源码摘要并校验其余源文件固定。样本失败、热身异常或代码变化会停止，不自动补跑、删除或用更好的样本替换。

约束识别收紧前曾启动一次旧版工具试跑；在其结果完成前决定不计分，源文件校验阻止后续轮次。记录仍保存在 `target/dedup-ab-20261031/`；正式十轮另建目录，避免混用版本或挑选样本。
