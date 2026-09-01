# jvm-heap-analyzer

离线 JVM 堆转储（.hprof）分析命令行工具。基于 **Eclipse MAT Parser** 做解析与对象图索引，
自建支配树 / GC Root 最短引用链 / 泄漏模式匹配与评分，输出可离线查看的中文 HTML 报告 + 结构化 JSON。

## 快速开始（推荐：零参数启动脚本）

```bat
gradlew.bat build                :: 只需构建一次（Gradle 用本机 8.11 发行版，不会联网下载）

:: Windows
analyze.bat -i dump.hprof -o report
:: Linux / macOS / Git-Bash
./analyze.sh -i dump.hprof -o report/
```

`analyze.bat / analyze.sh` 会：

1. **自动推算堆**：解析内存随对象数增长，公式 `堆 ≈ 转储GB × 2.5 + 1`
   （下限 4g，上限物理内存 60%）。实测 6GB/9300 万对象转储：6g 堆会陷入
   GC 风暴数小时无进展，16g 堆全流程仅 2 分钟。对象密度极高时手动
   `XMX_OVERRIDE=20g` 覆盖。
2. **`-b` 后台模式**：等价 `nohup ... > analyze.log 2>&1 &`，防 ssh 断连丢进程，
   `LOG=xx.log` 可改日志路径。
3. **大转储自动收紧**：≥4GB 且未显式传参时附加 `--top 10 --min-retained-mb 20`。
4. **解析心跳**：MAT Pass2 在亿级对象上可能长时间无输出，工具每 60 秒打印
   `[heartbeat] parsing... elapsed Xs, index written Y MB`——**大小在涨就是活着**，
   勿轻易 kill；配合 `jstat -gcutil <pid>` 看 O 列≈100 且 GCT 猛涨 = 堆给小了。

```bash
./analyze.sh                 # 零参数：自动取脚本目录最新的 *.hprof，报告输出到当前目录
./analyze.sh -b              # 后台 + analyze.log
./analyze.sh -b -i heap.hprof -o report/     # 显式指定
tail -f analyze.log
```

运行全程有进度：`[1/4]~[4/4]` 阶段行、每个检测器 `> XxxDetector running...`、
长静默阶段每 30~60 秒打印 `[progress]/[heartbeat]`（含已耗时与索引写入量，
数值在涨即正常）。

逃生舱：`XMX_OVERRIDE=8g`、`JAVA_OPTS=...`、`JAR=...`、`JAVA=...`、`LOG=...` 环境变量。

**服务器部署**：把 `jvm-heap-analyzer-1.0.0.jar` 与对应的 `analyze.sh`（Linux/macOS）
或 `analyze.bat`（Windows）**并排拷贝**即可，脚本会在同目录找到 jar；也可用 `JAR=/path/jar`
显式指定。fat-jar 自包含全部依赖（MAT/Equinox/FreeMarker/模板），服务器只需 JDK 17+。

也可以直接调 jar（此时需自己带 -Xmx，建议 ≥ 转储大小×30%）：

```bat
java -Xmx6g -jar build\libs\jvm-heap-analyzer-1.0.0.jar ^
     -i dump.hprof -o report\ --top 20 --max-path 8 --min-retained-mb 5
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `-i` | 输入 .hprof 文件路径 | 必填 |
| `-o` | 输出报告目录 | 必填 |
| `--top` | 每类检测结果最多展示条数 | 20 |
| `--max-path` | 引用链最大展示深度 | 8 |
| `--min-retained-mb` | 泄漏嫌疑最小 Retained Heap 阈值（MB） | 5 |

> 大转储（几 GB 级）支配树会自动降级为 MAT 内置 retained 索引（解析完成后即判断，不做进程内 DFS），分析器内存因此保持平稳。
| `--verbose` | 输出 MAT 解析进度细节 | 关 |

输出：

```text
report/
├── index.html      # 主报告（① 堆概览 ② Top 内存占用类 ③ 泄漏嫌疑+引用链+模式+建议
│                   #   ④ 大对象 ⑤ 可疑集合 ⑥ ThreadLocal ⑦ ClassLoader 异常）
├── summary.json    # 结构化摘要（CI 可二次消费）
└── assets/style.css
```

## 架构与模块

```text
com.analyzer
├── cli/AnalyzerMain            picocli 入口，调度全流程
├── parser/
│   ├── HprofParserAdapter      MAT 快照 open/close 封装（两段式索引解析，大堆不 OOM）
│   ├── MatRuntimeBootstrap     Equinox “plain 模式”引导（见下文“关键技术点”）
│   └── ConsoleProgressListener 解析进度
├── graph/
│   ├── HeapGraph               对象图视图：outRefs/inRefs/GC Roots/浅堆，全部按需读 MAT 索引
│   ├── HeapInstance ClassInfo  对象/类维度轻量 DTO
│   └── GcRootPathAnalyzer      反向引用 BFS 最短 GC Root 链，逐跳标注字段名/数组下标
├── analysis/
│   ├── DominatorCalculator     Lengauer-Tarjan 支配树 + Retained 后序累加 + 按类聚合
│   ├── DominatorResult         两种模式（自建树 / 超大堆降级用 MAT 内置 retained 索引）
│   ├── Detector + 5 个实现     统一接口：LeakSuspect / LargeObject / CollectionGrowth /
│   │                           ThreadLocalLeak / ClassLoaderLeak
│   └── LeakPatternMatcher      6 种预设泄漏模式 + 评分公式
├── report/
│   ├── HtmlReporter            FreeMarker 渲染 index.html，内联 CSS + assets/style.css
│   ├── JsonReporter            summary.json（Gson）
│   └── RecommendationBuilder   按模式输出中文修复建议
└── util/Fmt                    MB 格式化 / HTML 转义
```

评分公式（用于泄漏嫌疑排序）：

```text
score = Retained(MB) + 20×静态字段持有 + 15×线程持有 + 25×(大集合且Retained>10MB) + 10×ClassLoader
```

## 关键技术点：MAT 脱离 OSGi 运行

Eclipse MAT 是 OSGi bundle，`org.eclipse.mat.*` 未发布到 Maven Central（MAT 1.15 的 jar 已
提取到 `libs/mat`，均为 Java-8 字节码，兼容 JDK 17）。在纯 `java -jar` 环境要让它工作，
`MatRuntimeBootstrap` 做了四件事：

1. **扩展注册表**：`RegistryFactory.createRegistry(...)` 创建 plain 注册表并安装为全局 provider，
   再按固定 bundle 清单把各 jar 的 `plugin.xml` 以 `addContribution` 装载（贡献者名必须用
   bundle 符号名，扩展点全名 = 贡献者名 + id）。
2. **内容类型**：equinox 的 `ContentTypeManager` 建目录依赖 `ServiceCaller`（非框架环境必炸），
   替换为自研 `SimpleContentTypeManager`（按扩展名 + 魔数识别 hprof / MAT 索引）。
3. **Location/Preferences**：向 `InternalPlatform`、`PreferencesOSGiUtils` 的 ServiceTracker
   字段注入临时目录桩，并预初始化 `DataArea`。
4. **Plugin 单例**：反射构造 `ParserPlugin`/`HprofPlugin`/`MATPlugin` 并补齐其
   ExtensionTracker / ParserRegistry / Bundle 代理。

**fat-jar 资源冲突**：多个 jar 根目录都有 `plugin.xml`，直接合并会互相覆盖。
构建任务 `patchMatLibs` 把每个 jar 内的 `plugin.xml` 改名为
`eclipse-plugin/<bundle符号名>.xml`（唯一路径），程序端按同一约定加载。

## 开发与测试

- Gradle：wrapper 指向本机已有发行版
  `distributionUrl=file:/C:/Users/Administrator/.gradle/wrapper/dists/gradle-8.11-all/.../gradle-8.11-all.zip`，
  不会重新下载 Gradle；依赖库从 Maven Central 正常解析。
- 测试样本：`src/test/resources/sample-leak.hprof` 由测试目录下的
  `com.analyzer.tools.LeakDemo` 生成（内含静态 Map 增长、8MB byte[]、ThreadLocal 持有、
  3 个同型自定义 ClassLoader）。如需重新生成：

  ```bat
  gradlew.bat testClasses
  java -Xmx256m -cp "build\classes\java\test;build\resources\test" com.analyzer.tools.LeakDemo sample.hprof
  ```

- 单元测试：`./gradlew test`（5 个用例：解析、支配树、引用链、检测器、CLI 端到端）。

## 大堆注意事项

- MAT 会在 .hprof 同目录生成 `.index` 索引缓存（需要可写）；第二次打开直接复用。
- 可达对象超过 600 万时支配树自动降级为 MAT 内置 retained 索引（报告头部会标注）。
- 建议给分析进程 `-Xmx` 至少 2G；引用链查找带 2,000,000 次出队的搜索保护上限。

## 许可说明

Eclipse MAT 与 Equinox 组件为 EPL-2.0 许可，以库（jar）形式随工具分发；本项目代码为原创。
