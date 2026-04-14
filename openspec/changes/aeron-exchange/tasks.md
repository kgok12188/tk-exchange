# Tasks: match-engine Aeron 实现

> 每个 task 对应 design.md 中的一个功能点。
> 实现范围：`tk-match-engine` 模块。Aeron 1.50.0，SBE 1.34.1，Java 21，无 Spring Boot。

---

## Task 1: 清理旧架构代码

删除所有基于 Kafka / ZooKeeper / Chronicle Queue / Disruptor / Spring 的旧组件，只保留核心撮合引擎。

**删除以下文件（整目录/文件）：**
- `compare/` — DelayedFileDeletionService、LastWrite、MatchResultChecker、MatchResultMasterFileQueue、MatchResultSlaveFileQueue
- `config/LeaderElectionConfig.java`
- `config/MatchEngineConfig.java`
- `ha/MatchLeaderElectionService.java`
- `service/MatchManager.java`
- `service/MatchResultTailQueryService.java`
- `slot/MatchSlot.java`
- `slot/SlotTaskEventFactory.java`
- `slot/event/` — 全部（AddSymbolEvent、ComparedEvent、HaEvent、OrderSlotEvent、SlotEvent、SlotTaskEvent、SnapshotEvent）
- `snapshot/SnapshotFileHelper.java`
- `snapshot/SnapshotScheduler.java`
- `snapshot/SnapshotLoadResult.java`
- `snapshot/SnapshotMetadata.java`
- `snapshot/PlainStringBigDecimalSerializer.java`
- `engine/OrderCommandEnvelope.java`
- `src/main/resources/application.yml` （替换为 `aeron.properties`）
- 测试文件中的 `queue/ChronicleQueueTest.java`、`queue/OrderReqProducerRunner.java`（不再需要）

**保留不变：**
- `engine/MatchEngine.java`（后续 Task 2 修改）
- `engine/OrderBook.java`（后续 Task 3 修改）
- `engine/BookOrder.java`
- `engine/PriceLevel.java`
- `engine/PriceCodec.java`
- `engine/MarketRules.java`
- `engine/OrderIdDeduplicate.java`
- `engine/Roaring64NavigableMapWrapper.java`
- `engine/matcher/`（全部）
- `slot/ArrayStackBookOrder.java`（移动到 `engine/` 包）

- [x] 删除上述所有旧文件/目录

---

## Task 2: 更新 pom.xml — 移除 Spring Boot，添加 trading-protocol 依赖

`pom.xml` 已配置 Aeron 1.50.0 + SBE 插件（`src/main/resources/sbe`），需要：

1. 添加 `tk:trading-protocol:1.0` 依赖（内部引擎仍使用 OrderPayload、CancelPayload 等 DTO）
2. 移除 Spring Boot 相关依赖（spring-boot-starter-*、spring-kafka、zookeeper、chronicle 等）
3. pom 已有 maven-shade-plugin 打 fat-jar，mainClass 改为 `com.tk.match.MatchApplication`（保持不变）
4. 确认 SBE plugin 配置 `sourceSet` 指向 `${project.basedir}/src/main/resources/sbe`

- [x] 更新 pom.xml

---

## Task 3: 重构 MatchEngine — 移除 Kafka offset 语义

`MatchEngine.process()` 当前参数：`(OrderCommand cmd, long orderReqOffset, long timestamp)`

重构目标（对应 design.md §3、§6）：
- 新签名：`process(OrderCommand cmd, long seq, long timestamp)`
  - `seq` = 来自 `ClusteredService` 的 `nextMatchSeq`，用于 BookOrder 的价格-时间优先序
- 移除 `if (orderReqOffset <= book.getReqOffset()) return null` — Raft log 保证幂等，不需要这个检查
- `OrderBook.setReqOffset()` / `getReqOffset()` 调用一并移除
- `processUpdateMarket()` 签名同步调整
- 对应内部 `MatchResult`（engine 包，非 SBE）保持原样

- [x] 重构 MatchEngine.process() 签名及内部逻辑

---

## Task 4: 重构 OrderBook — 清理 Kafka/Chronicle 遗留字段

移除 `OrderBook` 中与旧架构相关的字段和方法（对应 design.md §3）：
- `reqOffset` / `setReqOffset()` / `getReqOffset()`
- `masterReqOffset` / `updateMasterReqOffsetIfGreater()`
- `comparedFileOffset` / `comparedFileQueueStartIndex`
- `snapshotOffset` / `getSnapshotOffset()`
- `matchResultTopic` / `getMatchResultTopic()`
- `getNavigableMapWrappers()`（仅供旧 SnapshotFileHelper 使用，删除）
- `loadFromSnapshot(SnapshotLoadResult, long)`（旧快照加载，删除；新快照由 `onLoadSnapshot` 直接调用 `restoreOrder`）

新增快照加载方法供 `MatchClusteredService` 使用：
```java
public void applyMarketConfig(MarketConfig cfg, long configVersion)  // 已有，保留
public long getAppliedMarketConfigVersion()                           // 已有，保留
public void restoreOrder(long orderId, long uid, int shardId,
                         String side, BigDecimal price, BigDecimal remainingVolume, long seq) // 已有，保留
```

- [x] 清理 OrderBook 字段和方法

---

## Task 5: SBE Schema — match-engine.xml

在 `src/main/resources/sbe/match-engine.xml` 创建完整 SBE schema（对应 design.md §4.4）。

**Composite 类型：**
```xml
<composite name="Decimal64">
  <type name="mantissa" primitiveType="int64"/>
  <type name="exponent" primitiveType="int8"/>
</composite>
<composite name="messageHeader" .../>  <!-- 标准 SBE header -->
```

**枚举（uint8）：** CommandType, Side, PriceType, TimeInForce, FinishStatus, RejectReason, BooleanType

**消息列表（templateId）：**
| ID | 消息名 | 方向 |
|----|--------|------|
| 1 | PushOrderCommand | ingress |
| 2 | CancelOrderCommand | ingress |
| 3 | UpdateMarketCommand | ingress |
| 10 | MatchResult | egress（含 trades group + finishOrders group）|
| 20 | SnapshotHeader | snapshot |
| 21 | SnapshotBookOrder | snapshot |
| 22 | SnapshotSymbolHeader | snapshot |

**MatchResult 字段：** matchSeq(i64), symbolId(u32), takerUid(i64), takerOrderId(i64), takerShardId(i32)
- group `trades`: index(i64), price(Decimal64), volume(Decimal64), buyUid(i64), sellUid(i64), buyOrderId(i64), sellOrderId(i64), buyShardId(i32), sellShardId(i32), takerOrderId(i64), takerUid(i64)
- group `finishOrders`: uid(i64), orderId(i64), status(FinishStatus), rejectReason(RejectReason), leaveAmount(Decimal64), leaveVolume(Decimal64), shardId(i32)

- [x] 创建 src/main/resources/sbe/match-engine.xml

---

## Task 6: SBE 编解码工具类

创建 `com.tk.match.sbe` 包下两个工具类（对应 design.md §4）：

### `Decimal64Codec.java`
BigDecimal ↔ SBE Decimal64（mantissa + exponent）互转工具：
```java
public static void encode(BigDecimal value, Decimal64Encoder enc);
public static BigDecimal decode(Decimal64Decoder dec);
```

### `SbeDecoder.java`
将 `DirectBuffer` 中的 SBE 命令消息解码为内部 DTO（`OrderCommand` / `CancelPayload` / `OrderPayload` / `MarketUpdatePayload`）：
```java
// 根据 messageHeader.templateId 分发
public static OrderCommand decode(DirectBuffer buffer, int offset, int length);
```
- templateId=1 → PushOrderCommand → `OrderCommand(PUSH_ORDER, OrderPayload{...})`
- templateId=2 → CancelOrderCommand → `OrderCommand(CANCEL_ORDER, CancelPayload{...})`
- templateId=3 → UpdateMarketCommand → `OrderCommand(UPDATE_MARKET, MarketUpdatePayload{...})`

### `SbeEncoder.java`
将内部 `engine.MatchResult` + `MatchResponse` + matchSeq + symbolId 编码为 SBE MatchResult 写入 `UnsafeBuffer`：
```java
public static int encodeMatchResult(
    long matchSeq, int symbolId,
    MatchResponse response,         // taker + trades + finishOrders
    UnsafeBuffer buffer, int offset);
```
- 无交易/无完单（空 MatchResult）：trades group count=0, finishOrders group count=0
- 返回编码后的 length（用于 offer 调用）

- [x] 创建 Decimal64Codec.java、SbeDecoder.java、SbeEncoder.java

---

## Task 7: MatchClusteredService — 核心 ClusteredService 实现

创建 `com.tk.match.cluster.MatchClusteredService`，实现 Aeron `ClusteredService` 接口（对应 design.md §3、§6、§7）。

**字段：**
```java
private final Map<Integer, MatchEngine> engines = new HashMap<>();
private final Map<Integer, String> symbolNames = new HashMap<>(); // symbolId → symbol name
private long nextMatchSeq = 0;
private long lastRecordedMatchSeq = -1;
private final TreeMap<Long, Long> matchSeqIndex = new TreeMap<>(); // matchSeq → archive position
private Cluster cluster;
private ExclusivePublication localPub;  // IPC, all nodes
private ExclusivePublication mdcPub;   // UDP MDC, Leader only
private final ClusterConfig config;
private final UnsafeBuffer encodingBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(4 * 1024 * 1024));
```

**核心逻辑：**

`onStart(Cluster cluster, Image snapshotImage)`:
- 保存 cluster 引用
- 创建 localPub（IPC channel）
- 如果 snapshotImage != null：调用 loadSnapshot(snapshotImage)
- 否则如果 Archive 已有录制：replay 找 lastRecordedMatchSeq，重建 matchSeqIndex

`onSessionMessage(...)`:
```
cmd = SbeDecoder.decode(buffer, offset, length)
symbolId = extractSymbolId(buffer, offset)
engine = engines.computeIfAbsent(symbolId, id -> createEngine(symbolNames.get(id)))
response = engine.process(cmd, nextMatchSeq, timestamp)
// 严格 1:1：response 为 null → 空 MatchResult
len = SbeEncoder.encodeMatchResult(nextMatchSeq, symbolId, response, encodingBuffer, 0)
if (nextMatchSeq > lastRecordedMatchSeq):
    pos = localPub.offer(encodingBuffer, 0, len)
    matchSeqIndex.put(nextMatchSeq, pos)
    if cluster.role() == LEADER:
        mdcPub.offer(encodingBuffer, 0, len)
nextMatchSeq++
```

`onRoleChange(Cluster.Role newRole)`:
- LEADER → 创建/激活 mdcPub（如未创建）
- FOLLOWER / CANDIDATE → 关闭 mdcPub

`onTerminate(Cluster cluster)`:
- 关闭 localPub、mdcPub

- [x] 创建 MatchClusteredService.java

---

## Task 8: Archive Spy 录制 + 启动去重 + matchSeq 索引

在 `MatchClusteredService.onStart()` 中实现（对应 design.md §7）：

**Archive 录制启动逻辑：**
```java
// channel: "aeron:ipc?alias=match-result"
// spy channel: "aeron-spy:aeron:ipc?alias=match-result"
long recordingId = findExistingRecording(archive, ipcChannel, streamId);
if (recordingId == NULL_VALUE) {
    archive.startRecording(spyChannel, streamId, SourceLocation.LOCAL);
    recordingId = waitForRecordingId(archive, ipcChannel, streamId);
} else {
    archive.extendRecording(recordingId, spyChannel, streamId, SourceLocation.LOCAL);
}
this.recordingId = recordingId;
```

**启动去重（replay 找 lastRecordedMatchSeq）：**
```java
if (recordingStopPosition > 0) {
    long sessionId = archive.startReplay(recordingId, 0, Long.MAX_VALUE, replayChannel, replayStreamId);
    Subscription replaySub = aeron.addSubscription(replayChannel, replayStreamId);
    // poll until no more fragments
    // decode each MatchResult → read matchSeq field → update lastRecordedMatchSeq, matchSeqIndex
    nextMatchSeq = lastRecordedMatchSeq + 1;
}
```

**matchSeqIndex 维护：**
- 每次 `localPub.offer()` 后：`matchSeqIndex.put(nextMatchSeq, claimedPosition)`
- Archive position 通过 `ExclusivePublication.position()` 在 offer 后获取

- [x] 实现 Archive spy 录制 + 启动去重 + matchSeqIndex 维护（在 MatchClusteredService）

---

## Task 9: Cluster 快照 — onTakeSnapshot / onLoadSnapshot

实现 `MatchClusteredService` 的快照方法（对应 design.md §9，替代旧 SnapshotFileHelper）。

**onTakeSnapshot(ExclusivePublication snapshotPub):**
1. 编码 `SnapshotHeader`（nextMatchSeq, symbolCount=engines.size()）
2. 遍历 engines.entrySet()，对每个 symbol：
   - 编码 `SnapshotSymbolHeader`（symbolId, orderCount, nextSeq=appliedMarketConfigVersion）
   - 调用 `engine.getBook().visitBookOrder(order -> encode SnapshotBookOrder)`
3. 所有消息通过 `snapshotPub.offer()` 写入
4. 使用 SBE 编码（模板 ID 20、21、22）

**onLoadSnapshot(Image snapshotImage):**
1. 创建 fragmentAssembler，poll 直到 snapshotImage.isEndOfStream()
2. 解码 SnapshotHeader → 恢复 nextMatchSeq
3. 解码 SnapshotSymbolHeader → 创建/获取对应 MatchEngine，设置 marketConfig version
4. 解码 SnapshotBookOrder → 调用 `book.restoreOrder(...)`
5. 快照加载完成后，Cluster 自动从对应 log position 继续 replay

- [x] 实现 onTakeSnapshot() 和 onLoadSnapshot()

---

## Task 10: ClusterConfig — 配置类

创建 `com.tk.match.cluster.ClusterConfig`，从系统属性（`-D` 参数）读取配置（对应 deploy 需求）：

```java
public record ClusterConfig(
    int nodeId,                    // -Dcluster.node.id=0
    String clusterMembers,         // -Dcluster.members=0,localhost,20110,20220,20330,20440,8010|...
    String archiveDir,             // -Dcluster.archive.dir=/data/match-engine/archive
    String clusterDir,             // -Dcluster.dir=/data/match-engine/cluster
    String aeronDir,               // -Daeron.dir=/tmp/aeron-match
    String mdcChannel,             // -Dmatch.mdc.channel=aeron:udp?control=0.0.0.0:40000|control-mode=dynamic
    int mdcStreamId,               // -Dmatch.mdc.stream.id=100
    String ipcChannel,             // aeron:ipc (固定)
    int ipcStreamId                // -Dmatch.ipc.stream.id=101
) {
    public static ClusterConfig fromSystemProperties();
}
```

- [x] 创建 ClusterConfig.java

---

## Task 11: MatchClusterNode — 主入口，启动 Aeron Cluster

创建 `com.tk.match.cluster.MatchClusterNode`，替代 Spring Boot 启动（对应 design.md §1）：

```java
public class MatchClusterNode implements AutoCloseable {

    public static void launch(ClusterConfig config) {
        MatchClusteredService service = new MatchClusteredService(config);

        MediaDriver.Context mdCtx = new MediaDriver.Context()
            .aeronDirectoryName(config.aeronDir())
            .threadingMode(ThreadingMode.SHARED)
            ...;

        Archive.Context archiveCtx = new Archive.Context()
            .aeronDirectoryName(config.aeronDir())
            .archiveDir(new File(config.archiveDir()))
            ...;

        ConsensusModule.Context consensusCtx = new ConsensusModule.Context()
            .clusterMemberId(config.nodeId())
            .clusterMembers(config.clusterMembers())
            .clusterDir(new File(config.clusterDir()))
            ...;

        ClusteredServiceContainer.Context containerCtx = new ClusteredServiceContainer.Context()
            .clusteredService(service)
            .clusterDir(new File(config.clusterDir()))
            ...;

        try (MediaDriver md = MediaDriver.launch(mdCtx);
             Archive archive = Archive.launch(archiveCtx);
             ConsensusModule cm = ConsensusModule.launch(consensusCtx);
             ClusteredServiceContainer csc = ClusteredServiceContainer.launch(containerCtx)) {
            // 保存 archive 引用给 service
            service.setArchive(AeronArchive.connect(new AeronArchive.Context()...));
            registerShutdownHook(md, archive, cm, csc);
            awaitShutdown();
        }
    }
}
```

注意：
- `ConsensusModule` + `ClusteredServiceContainer` 各自持有 AgentRunner，duty cycle 自动运行
- 主线程通过 `CountDownLatch` 或 `ClusteredServiceContainer.context().terminationHook()` 阻塞

- [x] 创建 MatchClusterNode.java

---

## Task 12: 更新 MatchApplication — 纯 Java main 入口

将 `MatchApplication.java` 改为不依赖 Spring Boot 的纯 Java 入口：

```java
public class MatchApplication {
    public static void main(String[] args) {
        ClusterConfig config = ClusterConfig.fromSystemProperties();
        MatchClusterNode.launch(config);
    }
}
```

同时：
- 删除 `@SpringBootApplication`、`@EnableScheduling` 注解
- 删除 `src/main/resources/application.yml`
- 创建 `src/main/resources/logback.xml`（基础日志配置）

- [x] 更新 MatchApplication.java 和配置文件

---

## Task 13: ArrayStackBookOrder 迁移至 engine 包

`ArrayStackBookOrder` 目前在 `slot` 包（`com.tk.match.slot`），`slot` 包删除后仍被 `OrderBook` 使用。

- 将文件移动到 `com.tk.match.engine.ArrayStackBookOrder`（或改为 `com.tk.match.cluster` 包创建时构造）
- 更新所有引用

- [x] 迁移 ArrayStackBookOrder 到 engine 包

---

## 实现顺序建议

```
Task 1 (清理) → Task 13 (迁移) → Task 2 (pom) → Task 5 (SBE schema) →
Task 6 (编解码) → Task 3 (MatchEngine 重构) → Task 4 (OrderBook 清理) →
Task 10 (ClusterConfig) → Task 7 (ClusteredService) → Task 8 (Archive+录制) →
Task 9 (快照) → Task 11 (ClusterNode) → Task 12 (入口)
```

---

## Task 14: 重命名 MarketConfig → MatchMarketConfig；删除 MarketUpdatePayload

对应 design.md §13.2。

**操作清单：**

1. `trading-protocol` 模块：
   - `MarketConfig.java` → 重命名为 `MatchMarketConfig.java`（类名、文件名同步）
   - `symbolName` 字段确认存在（原有）
   - 删除 `MarketUpdatePayload.java`（不再需要包装层）
   - 删除 `CommandType.java`（若已独立为枚举类；原为 SBE 生成，随 schema 删除 `CommandType` 枚举后自然消失）

2. 全局替换所有 `import com.tk.protocol.dto.MarketConfig` → `import com.tk.protocol.dto.MatchMarketConfig`：
   - `tk-match-engine`：MatchEngine, OrderBook, BookOrder, MarketRules, MatchClusteredService, matcher/*.java, 测试文件
   - `tk-open-api`：OrderController（如有直接引用）

3. `tk-common` 模块 `entity.MarketConfig` 新增字段：
   - `private BigDecimal minTradeQuoteAmount;`（DB 列：`min_trade_quote_amount`）
   - 新增转换方法（`toMatchMarketConfig()`）：
     ```java
     public MatchMarketConfig toMatchMarketConfig() {
         return MatchMarketConfig.builder()
                 .symbolId(this.id)
                 .symbolName(this.symbol)
                 .priceScale(this.priceScale != null ? this.priceScale : 2)
                 .qtyScale(this.numScale != null ? this.numScale : 8)
                 .minQty(this.numMin)
                 .minTradeQuoteAmount(this.minTradeQuoteAmount)
                 .build();
     }
     ```

- [x] 重命名 MarketConfig → MatchMarketConfig，全局替换引用
- [x] 删除 MarketUpdatePayload.java
- [x] entity.MarketConfig 新增 minTradeQuoteAmount 字段 + toMatchMarketConfig() 方法

---

## Task 15: SBE Schema 扩展（match-engine.xml）

对应 design.md §4.3、§4.4、§13.4。

**`match-engine.xml` 修改：**

1. **删除** `CommandType` 枚举（`<enum name="CommandType" ...>`）

2. **扩展** `RejectReason` 枚举，追加四个值：
   ```xml
   <validValue name="UNKNOWN_SYMBOL">13</validValue>
   <validValue name="MARKET_CLOSED">14</validValue>
   <validValue name="SYMBOL_ALREADY_EXISTS">15</validValue>
   <validValue name="CONFIG_VERSION_STALE">16</validValue>
   ```

3. **新增** `OpenMarketCommand`（templateId=4）：
   ```xml
   <sbe:message name="OpenMarketCommand" id="4" description="List new symbol (admin)">
       <field name="symbolId"            id="1" type="uint32"/>
       <field name="priceScale"          id="2" type="int32"/>
       <field name="qtyScale"            id="3" type="int32"/>
       <field name="minQty"              id="4" type="Decimal64"/>
       <field name="minTradeQuoteAmount" id="5" type="Decimal64"/>
       <field name="configVersion"       id="6" type="int64"/>
       <data  name="symbolName"          id="7" type="varAsciiEncoding"/>
   </sbe:message>
   ```

4. **新增** `CloseMarketCommand`（templateId=5）：
   ```xml
   <sbe:message name="CloseMarketCommand" id="5" description="Delist symbol (admin)">
       <field name="symbolId"      id="1" type="uint32"/>
       <field name="configVersion" id="2" type="int64"/>
       <field name="force"         id="3" type="BooleanType"/>
   </sbe:message>
   ```

5. **更新** `SnapshotSymbolHeader`（templateId=22），末尾追加：
   ```xml
   <field name="closed"     id="8" type="BooleanType"/>
   <data  name="symbolName" id="9" type="varAsciiEncoding"/>
   ```

- [x] 删除 CommandType 枚举
- [x] 扩展 RejectReason（4 个新值）
- [x] 新增 OpenMarketCommand（templateId=4）
- [x] 新增 CloseMarketCommand（templateId=5）
- [x] 更新 SnapshotSymbolHeader（closed + symbolName）

---

## Task 16: SbeDecoder / SbeEncoder 适配新消息

对应 design.md §4.4、§13.3。

**`SbeDecoder.java`：**
- 新增对 `OpenMarketCommand`（templateId=4）的解码：返回包含 symbolId、MatchMarketConfig、symbolName 的载体对象（新建 `OpenMarketPayload`，或直接用 `MatchMarketConfig`）
- 新增对 `CloseMarketCommand`（templateId=5）的解码：返回 symbolId、configVersion、force
- 保留 `UpdateMarketCommand`（templateId=3）解码，返回类型从 `MarketUpdatePayload` 改为 `MatchMarketConfig`（含 configVersion、force 附加字段）
- `decode()` 返回类型考虑统一为 `Object`，由 `onSessionMessage` 按 templateId cast；或拆为独立方法

**`SbeEncoder.java`：**
- `encodeSnapshotSymbolHeader()` 方法新增 `boolean closed` 和 `String symbolName` 参数
- 确认 `encodeMatchResult()` 对空 response（`null`）正确输出 trades=0 / finishOrders=0

- [x] SbeDecoder 支持 templateId=4 (OpenMarket) 和 templateId=5 (CloseMarket)
- [x] SbeEncoder.encodeSnapshotSymbolHeader 新增 closed + symbolName 参数
- [x] 更新 UpdateMarket 解码返回类型（去掉 MarketUpdatePayload）

---

## Task 17: MatchEngine 生命周期 + MatchClusteredService 分发重构

对应 design.md §13.3、§13.4。

**`MatchEngine.java`：**
- 新增 `private boolean closed = false;` 字段
- 新增 `boolean isClosed()` getter
- 新增 `void close(boolean force)` 方法：
  - `force=true`：调用 `book.cancelAllOrders()` 生成 finishOrders 列表后标记 closed
  - `force=false`：若 `book.getOrderCount() > 0` 则拒绝（返回失败），否则标记 closed
- 建议：`close()` 返回 `MatchResponse`（含被撤单的 finishOrders），保持与 `process()` 一致的输出风格

**`MatchClusteredService.java`：**

1. **删除**：
   - `registerSymbol(int, String)` 方法
   - `createDefaultEngine(String, int)` 方法
   - `engines.computeIfAbsent(...)` 逻辑

2. **重写 `onSessionMessage`**，按 templateId 分发（见 design.md §13.3）：
   - templateId=1/2：订单流，engines.get(symbolId) + null/closed 检查
   - templateId=3：UpdateMarket，engine.applyConfig()
   - templateId=4：OpenMarket，幂等创建 engine，symbolNames 更新
   - templateId=5：CloseMarket，engine.close(force)
   - 所有分支统一 `emitMatchResult(symbolId, response)` + `nextMatchSeq++`

3. **更新 `onTakeSnapshot`**：
   - `encodeSnapshotSymbolHeader(...)` 新增传入 `engine.isClosed()` 和 `symbolNames.get(symbolId)`

4. **更新 `loadSnapshot`**：
   - 解码 `SnapshotSymbolHeader` 时读取 `closed` 和 `symbolName`
   - `symbolNames.put(symbolId, symbolName)`
   - 若 `closed=T`：创建引擎后立即调用 `engine.close(false)`（或直接设置 closed 标记）

- [x] MatchEngine 新增 closed 标记 + close(force) 方法
- [x] MatchClusteredService.onSessionMessage 改为 templateId 分发
- [x] 删除 registerSymbol() 和 createDefaultEngine()
- [x] onTakeSnapshot / loadSnapshot 适配新快照字段

---

## Task 18: admin-api 集成（上币/下币/配置更新发送到 match-engine）

对应 design.md §13.5。

**依赖变更：**
- `tk-admin-api/pom.xml` 新增 `tk:trading-protocol:1.0` 依赖

**新增组件：**
- `MatchEngineAdminClient.java`：封装 `AeronCluster` client，提供：
  ```java
  void sendOpenMarket(MatchMarketConfig config);
  void sendCloseMarket(int symbolId, long configVersion, boolean force);
  void sendUpdateMarket(MatchMarketConfig config, long configVersion, boolean force);
  ```

**`MarkerConfigController.java` 更新：**
- `POST /market/add`：写入 MySQL 成功后，调用 `MatchEngineAdminClient.sendOpenMarket(entity.toMatchMarketConfig())`
- 新增 `POST /market/close`：调用 `MatchEngineAdminClient.sendCloseMarket(...)`
- 新增 `POST /market/update`：调用 `MatchEngineAdminClient.sendUpdateMarket(...)`

- [x] tk-admin-api 添加 trading-protocol 依赖
- [x] 实现 MatchEngineAdminClient（AeronCluster client，SBE 编码）
- [x] 更新 MarkerConfigController（上币 / 下币 / 更新配置 REST → Aeron）

---

## 更新后的实现顺序建议

```
（已完成 Task 1-13）

Task 14 (重命名 MatchMarketConfig + entity 扩展)
  → Task 15 (SBE schema 扩展)
  → Task 16 (SbeDecoder/SbeEncoder 适配)
  → Task 17 (MatchEngine lifecycle + ClusteredService 分发重构)
  → Task 18 (admin-api 集成)
  → Task 19 (match-engine HTTP admin 层 + UUID 关联)
  → Task 20 (admin-api 切换为 HTTP 调用)
```

---

## Task 19: match-engine HTTP admin 层（UUID 关联异步响应）

**背景**：admin-api 不应感知 Aeron。match-engine 作为边界服务，对外暴露 HTTP，对内通过 LocalClusterClient 推送到 Raft log。UUID 由 match-engine 节点生成，用于关联 HTTP 异步请求与 onSessionMessage 执行结果。

### 19a: SBE schema 新增 UUID 字段

`match-engine.xml` 三条 admin 命令各追加两个 int64 字段：

```xml
<!-- OpenMarketCommand -->
<field name="uuidHigh" id="8" type="int64"/>
<!-- symbolName vardata 移至末尾，id 改为 9 -->

<!-- CloseMarketCommand -->
<field name="uuidHigh" id="4" type="int64"/>
<field name="uuidLow"  id="5" type="int64"/>

<!-- UpdateMarketCommand -->
<field name="uuidHigh" id="8" type="int64"/>
<field name="uuidLow"  id="9" type="int64"/>
```

> 注意：SBE 要求固定字段在 vardata 之前，OpenMarketCommand 的 uuidHigh/uuidLow 在 symbolName 之前。

完成后重新运行 SBE 工具重新生成 Java 类。

- [x] match-engine.xml 三条 admin 命令新增 uuidHigh + uuidLow
- [x] 运行 SBE 工具重新生成

### 19b: match-engine 新增 spring-boot-starter-web

`tk-match-engine/pom.xml` 追加依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

配置 HTTP 端口（`application.yml`）：

```yaml
server:
  port: ${match.admin.port:8090}
```

- [x] pom.xml 新增 starter-web
- [x] application.yml 配置 admin HTTP 端口

### 19c: PendingCommandRegistry

新建 `com.tk.match.admin.PendingCommandRegistry`：
- `ConcurrentHashMap<String, CompletableFuture<AdminCommandResult>>`（key = UUID.toString()）
- `put(uuid, future)`：注册等待
- `tryComplete(uuid, result)`：在 onSessionMessage 中调用，若 uuid 存在则 complete，否则静默忽略
- `AdminCommandResult`：`boolean success; String message`

- [x] 创建 PendingCommandRegistry + AdminCommandResult

### 19d: LocalClusterClient

新建 `com.tk.match.admin.LocalClusterClient`（实现 `SmartLifecycle`）：
- 使用 `AeronCluster.connect(ingressEndpoints=match.cluster.ingress-endpoints)`
- 提供 `offer(buffer, offset, length)` 方法
- phase 高于 `MatchClusterNode`，确保集群先启动
- 配置项：`match.cluster.admin-ingress-endpoints`（可与普通 ingress 相同）

- [x] 创建 LocalClusterClient（SmartLifecycle，内嵌 AeronCluster client）

### 19e: AdminController

新建 `com.tk.match.admin.AdminController`（`@RestController`）：

```
POST /admin/openMarket   → OpenMarketCommand{uuid, ...}
POST /admin/closeMarket  → CloseMarketCommand{uuid, ...}
POST /admin/updateMarket → UpdateMarketCommand{uuid, ...}
```

- 接受 JSON 请求体（`symbolId`, `symbolName`, `priceScale` 等字段）
- 生成 UUID，注册 `pendingRegistry`
- SBE 编码后通过 `localClusterClient.offer()` 发送
- `future.get(5, TimeUnit.SECONDS)`，超时返回 503
- 成功返回 200 `{"success": true}`

- [x] 创建 AdminController（3 个 REST 端点）

### 19f: MatchClusteredService 补充 UUID 回调

在 `onSessionMessage` 的 admin 命令处理末尾（case 3/4/5），提取 uuid 并调用 `pendingRegistry.tryComplete(uuid, result)`：

```java
UUID uuid = new UUID(decoder.uuidHigh(), decoder.uuidLow());
if (!uuid.equals(NULL_UUID)) {
    pendingRegistry.tryComplete(uuid.toString(),
        new AdminCommandResult(true, "OK"));
}
```

`NULL_UUID = new UUID(0, 0)` 表示非 HTTP 发起的命令（如 trading-server 不带 UUID）。

- [x] MatchClusteredService admin case 末尾调用 pendingRegistry.tryComplete

---

## Task 20: admin-api 切换为 HTTP 调用

**目标**：admin-api 完全去除 Aeron 依赖，改为 HTTP 调用 match-engine admin endpoint。

### 20a: 清理 admin-api 的 Aeron 依赖

`tk-admin-api/pom.xml`：
- 删除 `io.aeron:aeron-cluster` 依赖
- 删除 `tk:trading-protocol` 依赖（不再需要 SBE）
- 增加 `org.springframework.boot:spring-boot-starter-webflux`（可选，也可直接用 RestTemplate）

- [x] pom.xml 移除 aeron-cluster 和 trading-protocol 依赖

### 20b: 删除 MatchEngineAdminClient.java

删除 `tk-admin-api/src/main/java/com/tk/futures/admin/cluster/MatchEngineAdminClient.java`

- [x] 删除 MatchEngineAdminClient.java

### 20c: 更新 MarkerConfigController

`MarkerConfigController` 改为通过 `RestTemplate` / `WebClient` 调用 match-engine HTTP：

```java
// POST http://match-engine:8090/admin/openMarket
restTemplate.postForObject(matchEngineAdminUrl + "/admin/openMarket", body, AdminResult.class)
```

配置项：`match-engine.admin-url=http://localhost:8090`

- [x] MarkerConfigController 改为 HTTP 调用 match-engine admin 端点
- [x] 配置 match-engine admin URL
