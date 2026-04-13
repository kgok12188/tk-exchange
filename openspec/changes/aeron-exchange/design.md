## Context

本设计描述交易系统基于 Aeron 的完整架构，覆盖 match-engine（指令共识）和 trading-server（结果共识）两侧。

```
                         open-api
                           │ REST
                           ▼
  ┌──────────────────────────────────────────────────┐
  │  trading-server (Spring Boot + Aeron Cluster)    │
  │  结果共识: 内存层 + 共识层 + 输出层              │
  └──────────┬──────────────┬──────────────┬─────────┘
             │              │              │
     matchOrderReq      response     tradingResult
     (MDC + Archive)   (MDC + Archive) (MDC + Archive)
             │              │              │
             ▼              ▼              ▼
      matchReplayMerge  open-api    flush-service → MySQL
      (Leader 独有)
             │
             ▼
  ┌──────────────────────────────────────────────┐
  │  match-engine (Aeron Cluster, 指令共识)      │
  │  单线程撮合, 严格 1:1, SBE                  │
  └────────────────────┬─────────────────────────┘
                       │
               MatchResult (matchSeq)
               (MDC + Spy Archive)
                       │
            ┌──────────┼──────────┐
            ▼                     ▼
   trading-server           行情 service
   (ReplayMerge)           (ReplayMerge)
```

## Goals / Non-Goals

**Goals:**

- 为 match-engine 设计基于 Aeron Cluster 的完整架构：共识、撮合、出口、录制、快照、恢复。
- 为 trading-server 设计结果共识三层架构：内存层、共识层、输出层（三条 MDC 流）。
- 定义 SBE 消息协议，替代当前 JSON 序列化。
- 明确 MDC + Spy + ReplayMerge 的数据分发与消费模型。
- 明确切主流程与 matchSeq 位点恢复机制。

**Non-Goals:**

- 不设计 open-api 网关层的 Aeron 接入方式。
- 不设计行情 service 的内部实现。
- 不改变 MatchEngine / OrderBook 的核心撮合逻辑。

## Decisions

### 1. match-engine 采用 Aeron Cluster + 指令共识

- **Decision**: match-engine 部署为 Aeron Cluster（3 节点），使用 Raft 共识对输入指令（OrderCommand）排序。所有节点执行相同的有序命令流，维护相同的 OrderBook 状态。
- **Rationale**:
  - match-engine 是纯确定性状态机：相同输入序列 → 相同输出。天然适合指令共识。
  - Aeron Cluster 内置 Raft，替代 ZooKeeper 选主 + Chronicle Queue 主从文件队列 + 自定义补发逻辑。
  - 所有节点状态一致，切主时无需补发、无数据丢失。

### 2. 单一 Cluster 覆盖所有 symbol

- **Decision**: 所有交易对在同一个 Aeron Cluster 中处理，不做 per-symbol 或 per-group 分组。
- **Rationale**:
  - 当前吞吐需求（~5 万 TPS）在 Aeron Cluster 单线程能力范围内（SBE + 单线程可达数十万 TPS）。
  - 单 Cluster 部署和运维简单，无需管理多组 Cluster 的拓扑和路由。
  - 未来若吞吐不足，可按 symbol group 拆分为多 Cluster，架构上预留这个扩展点。

### 3. 单线程撮合，删除多 slot 并行模型

- **Decision**: match-engine 内部改为单线程撮合。`ClusteredService.onSessionMessage()` 在 Cluster 共识线程上执行，按 `symbolId` 路由到对应的 `MatchEngine` 实例。删除 MatchSlot / MatchManager / pendingSlotEvents / Disruptor 等多线程调度组件。
- **内部结构**:
  - `engines: Map<Integer, MatchEngine>` — symbolId → MatchEngine（每个 MatchEngine 持有一个 OrderBook）。
  - `onSessionMessage(session, buffer)` → decode SBE → `engines.get(symbolId).process(command)` → encode MatchResult。
  - 运行时上币：通过 `UpdateMarketCommand` 指令添加新 symbol（走 Raft log，所有节点一致）。
- **Rationale**:
  - Aeron Cluster 的 `ClusteredService` 天然是单线程回调模型，与多 slot 不兼容。
  - 单线程消除了所有并发复杂度（slot 事件队列、跨线程投递、per-slot isMaster 等）。
  - 5 万 TPS 下单线程 + SBE 绰绰有余。
- **删除的组件**:
  - `MatchSlot`（500+ 行）、`MatchManager`、`SlotEvent` / `SlotEventType`
  - `pendingSlotEvents` 跨线程事件队列
  - Disruptor（match-engine 侧）
  - 每 slot 独立 consumer
- **保留的核心**:
  - `MatchEngine`：process(OrderCommand) → MatchResult
  - `OrderBook`：TreeMap + LinkedHashMap 订单簿
  - `BookOrder`、`PriceLevel`：订单和价格档位
  - Matcher 逻辑：LIMIT / MARKET / IOC / FOK / LIMIT_MAKER

### 4. SBE 替代 JSON 序列化

- **Decision**: 服务间协议从 JSON (fastjson2 ProtocolSerde) 迁移到 SBE (Simple Binary Encoding)。
- **Rationale**:
  - SBE 零拷贝、零 GC、纳秒级编解码，与 Aeron 的 DirectBuffer 体系天然集成。
  - 消息体积减小约 60%（典型 MatchResult: ~222 bytes vs JSON ~600 bytes）。
  - 消除 JSON 解析的 GC 压力和 CPU 开销。

#### 4.1 BigDecimal 表示：Decimal64 composite

- **Decision**: BigDecimal 字段统一使用 SBE composite 类型 `Decimal64 { mantissa: int64, exponent: int8 }`。
- **示例**: price = 64523.75 → mantissa = 6452375, exponent = -2。
- **Rationale**: 自描述（不依赖外部 scale 约定）、精度充足（int64 mantissa）、SBE 标准做法。不选择 fixed-scale int64 方案，因为不同 symbol 的 scale 不同且 scale 会变更。

#### 4.2 symbol 表示：symbolId (uint32)

- **Decision**: wire 协议中 symbol 使用 `symbolId: uint32`，不传字符串。symbolId ↔ symbol 映射通过 `UpdateMarketCommand` 同步。
- **Rationale**: 定长 4 bytes，避免变长字符串在 SBE 消息中的性能开销；交易对配置本来就需要管理，symbolId 是自然延伸。

#### 4.3 枚举类型

SBE 中定义以下枚举（uint8 编码）：

| 枚举 | 值 |
|------|-----|
| `CommandType` | PUSH_ORDER(0), CANCEL_ORDER(1), UPDATE_MARKET(2) |
| `Side` | BUY(0), SELL(1) |
| `PriceType` | LIMIT(0), MARKET(1), LIMIT_MAKER(2) |
| `TimeInForce` | GTC(0), IOC(1), FOK(2) |
| `FinishStatus` | COMPLETED(0), CANCEL(1), PART_CANCEL(2), EXCEPTION(3), REJECT(4), POST_ONLY_REJECT(5) |
| `RejectReason` | NONE(0), INVALID_ORDER_ID(1), DUPLICATE_ORDER_ID(2), ORDER_EXPIRED(3), INVALID_PRICE_TYPE(4), INVALID_PRICE(5), PRICE_TICK_INVALID(6), INVALID_QUANTITY(7), INVALID_NOTIONAL(8), INVALID_TIME_IN_FORCE(9), POST_ONLY_WOULD_CROSS(10), FOK_NOT_FILLABLE(11), UNKNOWN(255) |

#### 4.4 SBE 消息定义

**拆分消息而非扁平 union**：当前 `OrderCommand` 根据 `type` 字段使用不同的 payload（pushPayload / cancelPayload / marketUpdatePayload）。SBE 中拆分为独立消息，通过 `messageHeader.templateId` 区分。

| 消息 | ID | 方向 | 字段 |
|------|-----|------|------|
| **PushOrderCommand** | 1 | trading-server → match-engine | symbolId(u32), orderId(i64), uid(i64), shardId(i32), marketId(i64), side(Side), priceType(PriceType), timeInForce(TimeInForce), price(Decimal64), volume(Decimal64), amount(Decimal64), createTime(i64) |
| **CancelOrderCommand** | 2 | trading-server → match-engine | symbolId(u32), orderId(i64), uid(i64), shardId(i32) |
| **UpdateMarketCommand** | 3 | trading-server → match-engine | symbolId(u32), priceScale(i32), qtyScale(i32), minQty(Decimal64), minTradeQuoteAmount(Decimal64), configVersion(i64), force(BooleanType) |
| **MatchResult** | 10 | match-engine → consumers | matchSeq(i64), symbolId(u32), takerUid(i64), takerOrderId(i64), takerShardId(i32); **group trades**: index(i64), price(Decimal64), volume(Decimal64), buyUid(i64), sellUid(i64), buyOrderId(i64), sellOrderId(i64), buyShardId(i32), sellShardId(i32), takerOrderId(i64), takerUid(i64); **group finishOrders**: uid(i64), orderId(i64), status(FinishStatus), rejectReason(RejectReason), leaveAmount(Decimal64), leaveVolume(Decimal64), shardId(i32) |

**快照消息**（Cluster onTakeSnapshot / onLoadSnapshot 使用）：

| 消息 | ID | 用途 | 字段 |
|------|-----|------|------|
| **SnapshotHeader** | 20 | 快照头 | nextMatchSeq(i64), symbolCount(i32) |
| **SnapshotSymbolHeader** | 22 | 每个 symbol 的快照头 | symbolId(u32), orderCount(i32), nextSeq(i64) |
| **SnapshotBookOrder** | 21 | 每个挂单 | symbolId(u32), orderId(i64), uid(i64), shardId(i32), side(Side), price(Decimal64), volume(Decimal64), remainingVolume(Decimal64), amount(Decimal64), remainingAmount(Decimal64), seq(i64) |

**不再需要作为 SBE 消息的类型**:

- `TradingSettle` — 删除 message-dispatch 后不再是协议消息，trading-server 内部从 MatchResult 导出。
- `Ticket` — 由 trading-server 内部从 MatchResult.trades 导出。
- `TradingRequest` / `TradingResponse` — open-api 走 REST/JSON，不走 SBE。

### 5. MDC 出口：Dynamic Multi-Destination-Cast

- **Decision**: match-engine Leader 通过 Aeron MDC Publication（Dynamic control-mode）发布 MatchResult。所有消费者（trading-server 各 shard、行情 service）作为 MDC subscriber 自行连接。
- **MDC 模式**: Dynamic MDC — publisher 暴露 control endpoint，subscriber 主动注册。match-engine 不需要知道消费者地址。
- **Channel 配置示例**: `aeron:udp?control=0.0.0.0:40000|control-mode=dynamic`
- **Rationale**:
  - match-engine 不感知消费者拓扑，增减 trading-server shard 或行情实例无需变更 match-engine 配置。
  - MDC 在 driver 层面多播，单次序列化 + 多目标发送，效率最高。

### 6. 严格 1:1 输入输出 + matchSeq 全局序号

- **Decision**: 每条进入 Raft log 的命令（PUSH_ORDER / CANCEL_ORDER / UPDATE_MARKET）**必须恰好产出一条 MatchResult**，无一例外。每条 MatchResult 携带全局递增的 `matchSeq`（int64，从 0 开始），作为唯一的排序、去重、间隙检测依据。
- **1:1 保证**:
  - ClusteredService 层实现：每次 `onSessionMessage` 必然产出一条 MatchResult。
  - 即使 `MatchEngine.process()` 返回 null（命令无实质结果），也包装为空 MatchResult（trades=[], finishOrders=[]）。
  - matchSeq 严格连续 [0, N]，无间隙、不跳号。
- **UPDATE_MARKET 的输出场景**:
  - 无影响（参数变更但存量挂单都合规）→ 空 MatchResult。
  - force=false 且存在不合规挂单 → 拒绝应用，空 MatchResult。
  - force=true 且触发撤单 → MatchResult 包含被强制撤销的 finishOrders。
- **不暴露 orderReqOffset**: Raft log position 是 Cluster 内部实现细节，不泄露到输出协议。matchSeq 是唯一的外部排序标识。
- **Rationale**:
  - 消费者间隙检测：收到 matchSeq N 后下一条必须是 N+1，否则告警。无歧义。
  - 对账：Raft log 已应用 M 条 entry → Archive 中恰好有 M 条 MatchResult（matchSeq 0 到 M-1）。
  - 单线程撮合天然保证 matchSeq 连续，无需原子操作。
  - 协议与 Aeron Cluster 实现解耦：若将来换底层共识，MatchResult 格式不变。

### 7. 双 MediaDriver + 双 Archive + Spy 录制

- **Decision**: 每个 Cluster 节点运行两个完全隔离的 MediaDriver，各自挂载独立 Archive。集群侧负责 Raft 共识，MDC 侧负责撮合结果发布与 spy 录制。所有节点（Leader 和 Follower）都发布到各自的 MDC 并通过 spy 零拷贝录制。

- **双 MediaDriver 架构**:
  ```
  ┌──────────────────────────────┐    ┌──────────────────────────────────┐
  │  Cluster MediaDriver         │    │  MDC MediaDriver                 │
  │  (Consensus / Archive-1)     │    │  (MatchResult 发布 / Archive-2)  │
  │                              │    │                                  │
  │  Archive-1: 共识日志 / 快照  │    │  ExclusivePublication (MDC)      │
  │  ConsensusModule             │    │       │                          │
  │  ClusteredServiceContainer   │    │  aeron-spy: + LOCAL              │
  │                              │    │       ▼                          │
  │  互不关联 ──────────────────┼────┼── Archive-2: spy 录制撮合结果    │
  └──────────────────────────────┘    └──────────────────────────────────┘
  ```

- **MDC publication + spy 录制**:
  - 所有节点将 MatchResult offer 到 MDC publication（UDP Dynamic MDC）。
  - MDC Archive（Archive-2）通过 `aeron-spy:` + `SourceLocation.LOCAL` 从同 driver 的 publication log buffer 零拷贝录制，不走网络。
  - 下游消费者可连接任意节点的 MDC 订阅实时流，或连接该节点的 Archive-2 发起 ReplayMerge。

- **扩展录制 (extend recording)**:
  - 使用 `AeronArchive.extendRecording()` 确保 `recordingId` 在重启后保持不变。
  - 消费者可以用固定的 recordingId 发起 ReplayMerge，不受 match-engine 重启影响。

- **启动去重**:
  - 启动时若已有 Archive-2 录制，先本地 replay 找到 `lastRecordedMatchSeq`。
  - 设 `nextMatchSeq = lastRecordedMatchSeq + 1`。
  - Cluster 回放 Raft log 期间，matchSeq ≤ lastRecordedMatchSeq 的 MatchResult 跳过写入，避免重复。

- **matchSeq → Archive position 索引**:
  - 内部维护 `TreeMap<Long, Long>` — key=matchSeq, value=archive recording position。
  - 启动时从 Archive-2 replay 重建，运行时每次 offer 后更新。
  - 用途：消费者指定 matchSeq 查询对应 archive position，从该位置发起 ReplayMerge。

- **Rationale**:
  - 双 MediaDriver 完全隔离：集群共识与撮合结果输出互不干扰，任一侧故障不影响另一侧。
  - 所有节点维护完整 Archive-2（确定性相同结果），切主时新 Leader 的 Archive 已有完整历史。
  - Spy 录制零拷贝、与撮合线程解耦。
  - 扩展录制保证 recordingId 稳定，消费者无需感知重启。

### 8. ReplayMerge：消费者的统一入口

- **Decision**: trading-server 和行情 service 通过 Aeron Archive 的 **ReplayMerge** 消费 match-engine 输出。ReplayMerge 自动处理断线追赶和实时消费的无缝衔接。
- **工作原理**:
  1. 消费者记录上次处理到的 `lastProcessedMatchSeq` 和对应的 `archivePosition`。
  2. 创建 ReplayMerge，连接 match-engine 节点的 Archive（replay 通道）和 MDC（live 通道）。
  3. ReplayMerge 从 archivePosition 开始 replay，自动与 live MDC 合并。
  4. 消费者始终使用同一个 `replayMerge.poll(handler, fragmentLimit)` 接口，无需区分阶段。
  5. 消费者用 matchSeq 做间隙检测：收到 matchSeq N 后下一条必须是 N+1，否则告警。
- **match-engine 切主时的消费者行为**:
  - 因为所有节点维护相同的 Archive-2（见 §7），消费者可连接任意节点的 MDC Archive。
  - 检测到 live MDC 中断后，重建 ReplayMerge，连接新 Leader 的 Archive-2 和 MDC。
  - 从上次的 archivePosition 继续，用 matchSeq 去重，无数据丢失。
- **Rationale**:
  - 统一的 replay + live 消费模型，无需手动 offset 管理。
  - 一个 API 解决追赶 + 实时两个阶段，消除应用层的 gap 检测和状态切换逻辑。

### 9. Cluster 快照替代自定义快照

- **Decision**: match-engine 的 OrderBook 快照由 Aeron Cluster 内置机制管理（`onTakeSnapshot` / `onLoadSnapshot`），替代当前的 SnapshotFileHelper + SnapshotScheduleService。
- **onTakeSnapshot(snapshotPublication)**:
  - 先编码 `SnapshotHeader`（nextMatchSeq, symbolCount），写入当前全局 matchSeq 状态。
  - 遍历所有 `engines` 中的 MatchEngine，对每个 symbol：编码 `SnapshotSymbolHeader`（symbolId, orderCount, nextSeq），然后遍历 OrderBook 的所有挂单编码为 `SnapshotBookOrder`。
  - 通过 `snapshotPublication.offer(buffer)` 写入。
  - 使用 SBE 编码（非 JSON），与 wire 协议一致。
- **onLoadSnapshot(snapshotImage)**:
  - 从 `snapshotImage` 读取 `SnapshotHeader`，恢复 `nextMatchSeq`。
  - 解码各 `SnapshotSymbolHeader` + `SnapshotBookOrder`，按 seq 升序 restoreOrder 重建每个 symbol 的 OrderBook。
  - Cluster 自动从快照对应的 log position 继续回放后续命令。
- **删除的组件**:
  - `SnapshotFileHelper`（自定义文件格式、多候选回退校验）
  - `SnapshotScheduleService`（@Scheduled 定时器）
  - `MatchSlot.submitSnapshotRequest`（queue 内调度）
  - `snapshot-dir` / `snapshot-interval-ms` 等配置项
- **Rationale**:
  - Aeron Cluster 自动管理快照生命周期（触发、存储、清理、恢复），无需应用层调度。
  - 快照与 Raft log position 绑定，恢复后自动从正确位置继续，无需手动 seek。
  - SBE 编码比 JSON 更紧凑、解析更快。

### 10. message-dispatch 模块已删除

- **Decision**: 彻底删除 message-dispatch 模块（代码 + 依赖）。trading-server Leader 直接通过 ReplayMerge 订阅 match-engine 的 MDC 输出，在内部完成 MatchResult → per-user 拆分和路由。
- **trading-server 内部处理**:
  - Leader 订阅全量 MatchResult 流。
  - 对每条 MatchResult，遍历 trades 和 finishOrders，按 `buyShardId` / `sellShardId` / `shardId` 过滤属于本 shard 的数据。
  - 按 uid hash 路由到对应 RingBuffer 处理。
- **Rationale**:
  - 消除一个独立服务的部署和运维开销。
  - Aeron MDC 天然支持多订阅者，trading-server 各 shard 和行情 service 可直接订阅，无需中间层。
  - match-engine 不感知 shard 拓扑（只发全量 MatchResult），解耦干净。

### 11. match-engine Ingress：Aeron Cluster Client Sessions

- **Decision**: trading-server 各 shard 的 Leader 作为 Aeron Cluster **client**，通过 session 向 match-engine Cluster 发送 OrderCommand（SBE 编码的 PushOrderCommand / CancelOrderCommand / UpdateMarketCommand）。
- **Session 管理**:
  - 每个 trading-server shard Leader 与 match-engine Cluster 建立一个 Aeron Cluster client session。
  - Session 在 trading-server Leader 启动时建立，切主时重建。
  - match-engine Cluster 通过 session 接收命令，命令进入 Raft log 排序后由 `onSessionMessage` 处理。
- **Rationale**:
  - Aeron Cluster 的 client session 是标准的 ingress 模式，天然支持请求路由和背压。

### 12. trading-server 结果共识：三层架构

#### 12.0 保留 Spring Boot

trading-server 保留 Spring Boot 框架：
- REST API 接口（open-api 接入 NEW_ORDER / CANCEL_ORDER 请求）。
- `@ConfigurationProperties` 管理 Aeron Cluster、MDC、RingBuffer 等配置。
- `SmartLifecycle` 管理 Aeron Cluster 节点的启动 / 关闭生命周期（与 match-engine 模式一致）。
- Spring DI 组装 ClusteredService 实现、Egress 组件、REST Controller。

Aeron Cluster 作为 Spring 容器内的 `SmartLifecycle` bean 运行，不替代 Spring 本身。

#### 12.1 为什么用结果共识

trading-server 处理 NEW_ORDER 时有外部副作用：
- 发送 order_req 给 match-engine
- 发送 response 给 open-api
- 写 trading_result 给 flush-service

如果所有副本都执行命令，会产生 3 份重复副作用。因此采用**结果共识**：只有 Leader 接收外部请求并处理，将处理结果写入 Raft log，全节点从 Raft log 更新状态并输出三条流。

#### 12.2 架构总览

```
                     open-api
                       │ REST (NEW_ORDER / CANCEL_ORDER)
                       ▼
              ┌─────────────────────────────────────────────────────────┐
              │              trading-server (Spring Boot)               │
              │                                                         │
              │  ┌───────────────────────────────────────────────────┐  │
              │  │  内存层 (Leader 独有活跃路径)                     │  │
              │  │                                                   │  │
              │  │  REST Controller                                  │  │
              │  │       │                                           │  │
              │  │       ▼                                           │  │
              │  │  RingBuffer[0..N-1]  (按 uid hash 分区)          │  │
              │  │  每个 RingBuffer 持有一组 TradingAccount          │  │
              │  │       │ 校验 → 冻结 → 生成 delta                 │  │
              │  │       │ (不查询共识层)                            │  │
              │  │       ▼                                           │  │
              │  │  处理 MatchResult (ReplayMerge 消费)              │  │
              │  │       │ 结算 → 释放冻结 → 生成 delta             │  │
              │  │       ▼                                           │  │
              │  │  delta 推入 Raft log ─────────────────────┐      │  │
              │  └───────────────────────────────────────────┼──────┘  │
              │                                              │         │
              │  ┌───────────────────────────────────────────┼──────┐  │
              │  │  共识层 (Aeron Cluster / Raft log)        │      │  │
              │  │                                           ▼      │  │
              │  │  onSessionMessage(delta)                         │  │
              │  │       │                                          │  │
              │  │       ├── 更新 TradingAccount 共识副本           │  │
              │  │       ├── 更新 ringBufferMatchSeq[i]             │  │
              │  │       └── 推送给输出层                           │  │
              │  │                                                  │  │
              │  │  Follower: Raft log → 共识层更新                 │  │
              │  │            → 通知内存层覆盖                      │  │
              │  └──────────────────────────────────────────────────┘  │
              │                                                         │
              │  ┌──────────────────────────────────────────────────┐   │
              │  │  输出层 (三条 MDC 流 + Spy Archive)              │   │
              │  │                                                  │   │
              │  │  ┌──────────────┐ ┌──────────┐ ┌─────────────┐  │   │
              │  │  │matchOrderReq │ │ response │ │tradingResult│  │   │
              │  │  │  MDC+Spy     │ │ MDC+Spy  │ │  MDC+Spy    │  │   │
              │  │  └──────┬───────┘ └─────┬────┘ └──────┬──────┘  │   │
              │  └─────────┼───────────────┼─────────────┼─────────┘   │
              └────────────┼───────────────┼─────────────┼─────────────┘
                           │               │             │
                           ▼               ▼             ▼
                  matchReplayMerge     open-api     flush-service
                  (Leader 独有)                      → MySQL
                       │
                       ▼
                  match-engine
                  (Aeron Cluster Client)
```

#### 12.3 三层详解

##### 12.3.1 内存层：RingBuffer 分区

```
  REST request / MatchResult
          │
    uid hash → slot
          │
          ▼
  ┌─────────────────────────────────────────────────────┐
  │  RingBuffer[0]  │  RingBuffer[1]  │ ... │  [N-1]   │
  │  uid群A 的       │  uid群B 的      │     │          │
  │  TradingAccount  │  TradingAccount │     │          │
  │  (余额/订单/     │  (余额/订单/    │     │          │
  │   持仓/冻结)     │   持仓/冻结)    │     │          │
  └─────────────────────────────────────────────────────┘
  每个 RingBuffer 内串行处理，保证单用户顺序性。
  RingBuffer 之间并行，提供 shard 内并发能力。
```

- **内存层与共识层数据隔离**：两层各自维护独立的 TradingAccount 数据副本，读写路径不交叉。
- **Leader**：接收 REST 请求和撮合结果，路由到对应 RingBuffer，在内存层中直接处理（校验 / 冻结 / 结算），**不查询共识层数据**。处理完毕后将 delta 推入 Raft log。
- **Follower**：不接收 REST 请求、不消费 MatchResult。共识层数据更新后**全量覆盖**内存层。

##### 12.3.2 共识层：Raft log + 独立数据副本

共识层通过 Aeron Cluster 的 Raft log 维护独立的 TradingAccount 数据副本。

**共识层元数据**：

| 元数据 | 说明 |
|--------|------|
| `ringBufferCount` | RingBuffer 数量，运行期间不可变更 |
| `ringBufferMatchSeq[i]` | 第 i 个 RingBuffer 已处理的最大 matchSeq |
| TradingAccount 数据 | 全量账户余额、订单、持仓、冻结的共识副本 |

**数据流方向**：

```
  Leader 路径:
    REST / MatchResult → 内存层处理 → delta → Raft log
                                                  │
                                              共识提交
                                                  │
                                   ┌──────────────┼──────────────┐
                                   ▼              ▼              ▼
                              Node-0(L)      Node-1(F)      Node-2(F)
                              共识层更新     共识层更新     共识层更新
                              + 输出三条流   + 输出三条流   + 输出三条流
                                             + 覆盖内存层   + 覆盖内存层
```

- **Leader → 共识层**：内存层处理完毕后，将 delta（含 `ringBufferMatchSeq` 位点）推入 Raft log。Leader 处理过程中不依赖共识层，处理性能不受共识延迟影响。
- **Follower 数据流**：Raft log 提交 → 共识层数据更新 → 全量覆盖内存层 TradingAccount。Follower 的内存层完全由共识层驱动。
- **输出三条流**：全节点在 Raft log 提交后输出三条 MDC 流到本地 Archive（spy 录制），全节点 Archive 内容一致。

##### 12.3.3 输出层：三条 MDC 流 + 双 MediaDriver

输出层采用与 match-engine 相同的双 MediaDriver + 双 Archive 架构（见 §7），分为三条独立的流。**全节点**在处理 Raft log 提交时输出。

| 流 | 内容 | 消费者 | 用途 |
|-----|------|--------|------|
| **matchOrderReq** | PushOrderCommand / CancelOrderCommand | matchReplayMerge → match-engine | 撮合指令 |
| **response** | 订单响应（接受 / 拒绝 / 状态更新） | open-api | 响应用户请求 |
| **tradingResult** | 结算结果（账户变更、订单更新、成交记录） | flush-service → MySQL | 持久化 |

每条流的架构：

```
  共识层 onSessionMessage
       │
       ▼ encode + offer
  ┌──────────────────────────────────────────────┐
  │  MDC MediaDriver (与 Cluster 的 MediaDriver  │
  │  完全隔离)                                   │
  │                                              │
  │  ExclusivePublication (UDP MDC Dynamic)       │
  │       │                                      │
  │  aeron-spy: + SourceLocation.LOCAL            │
  │       ▼                                      │
  │  MDC Archive: spy 录制到本地                  │
  └──────────────────────────────────────────────┘
       │ (MDC 网络)
       ▼
  下游消费者 (ReplayMerge 订阅)
```

- 全节点 offer 到 MDC publication，spy 录制到本地 Archive。
- 扩展录制（extend recording）：确保 recordingId 跨重启稳定。
- 全节点 Archive 内容一致，切主后消费者可连接新 Leader 的 Archive。
- 消费者通过 ReplayMerge 订阅，支持断线追赶 + 实时消费。

#### 12.4 切主流程

三种切主场景共享相同的状态重建路径：`onTerminate` → `onStart`（加载快照 + 回放 Raft log）→ 共识层**全量覆盖**内存层。

##### 12.4.1 切主场景

| 场景 | 触发条件 | 旧 Leader 行为 | 新 Leader 行为 |
|------|----------|----------------|----------------|
| Leader 处理慢被强制切主 | 心跳超时 / Cluster 判定 | 收到 `onRoleChange(FOLLOWER)` → `onTerminate` → 服务重启 | 新 Leader `onStart` → 从快照 + log 重建 |
| Leader 进程崩溃 | 进程退出 | 无（已崩溃） | 同上 |
| Leader 没宕机，主动让出 | 运维触发 / Cluster 重配置 | `onRoleChange(FOLLOWER)` → `onTerminate` → 重启并全量从共识层覆盖内存层 | 同上 |

##### 12.4.2 状态重建流程

```
  新 Leader 当选 (onRoleChange → LEADER)
       │
       ▼
  onStart(cluster, snapshotImage)
       │
       ├── loadSnapshot: 从快照重建全量 TradingAccount
       │   + ringBufferMatchSeq[0..N-1]
       │   + ringBufferCount
       │
       ├── 回放后续 Raft log (自动)
       │   → onSessionMessage 逐条应用 delta
       │   → 共识层 TradingAccount 逐步追赶到最新
       │
       ├── 共识层数据 → 全量覆盖内存层
       │   (所有 RingBuffer 的 TradingAccount 从共识层拷贝)
       │
       ├── 推送 LeaderChange(memberId) 进入 Raft log
       │   → 全节点处理此消息，记录 activeMemberId = memberId
       │   → 此后只接受 activeMemberId 匹配的节点推送的 delta
       │   → 其他节点的推送被拒绝（旧 Leader 的残留 delta 被丢弃）
       │
       ├── 本节点处理到 LeaderChange 且 memberId == 自身
       │   → 切主确认完成
       │   → 从共识层获取 ringBufferMatchSeq，确保完整一致
       │
       ├── 恢复 matchReplayMerge:
       │   min(ringBufferMatchSeq) → 消费起点
       │   从该 matchSeq 开始 ReplayMerge 消费 MatchResult
       │       matchSeq ≤ 某 RingBuffer 已处理位点 → 跳过
       │       matchSeq > 某 RingBuffer 已处理位点 → 正常处理
       │
       └── 开始接收 REST 请求
```

##### 12.4.3 LeaderChange 消息：隔离令牌

`LeaderChange(memberId)` 不仅是"确认前序日志已处理"的标记，更是**隔离令牌（fencing token）**：

- **写入 Raft log**：新 Leader 当选后，立即向共识层推送 `LeaderChange(memberId)` 消息，进入 Raft log。
- **全节点记录**：所有节点（Leader + Follower）处理此消息时，记录 `activeMemberId = memberId`。
- **拒绝非法推送**：此后共识层只接受 `activeMemberId` 匹配的节点推送的业务 delta。旧 Leader 残留的、尚未提交的 delta 因 memberId 不匹配而被拒绝，防止脏数据进入共识。
- **切主确认**：当推送 LeaderChange 的节点自身处理到这条消息（`LeaderChange.memberId == 本节点 memberId`），说明：
  1. 该消息之前的所有 Raft log 条目都已被共识层处理完毕。
  2. 共识层的 `ringBufferMatchSeq` 是完整、一致的。
  3. 切主正式完成，可以开始接收 REST 请求和消费 MatchResult。

```
  时间线:  旧 Leader (node-0)          新 Leader (node-1)
  ───────────────────────────────────────────────────────────
  t0       正常推送 delta
  t1       心跳超时 / 崩溃
  t2                                   当选 Leader
  t3                                   推送 LeaderChange(memberId=1)
  t4       (残留 delta 到达)
           → 拒绝: activeMemberId=1    
             != 发送方 memberId=0      全节点记录 activeMemberId=1
  t5                                   自身处理到 LeaderChange
                                       → 切主确认完成
  t6                                   开始接收 REST + MatchResult
```

- **为什么取 `min(ringBufferMatchSeq)`**：不同 RingBuffer 处理不同用户的 MatchResult，各自进度可能不同。取最小值确保不丢失任何尚未处理的数据，已处理的通过 matchSeq 比较跳过。
- **RingBuffer 数量不可变**：共识层记录 `ringBufferCount`，运行期间不允许变更，保证 Leader 和 Follower 的分区映射一致。

##### 12.4.4 旧 Leader 降级

旧 Leader（非崩溃场景）收到 `onRoleChange(FOLLOWER)` 后：

1. `onTerminate` → 停止 REST 请求接收、停止 matchReplayMerge、停止消费 MatchResult。
2. `onStart` 重新加载快照 + 回放 log → 共识层数据**全量覆盖**内存层（清除内存层可能存在的脏数据）。
3. 以 Follower 角色运行：Raft log → 共识层更新 → 覆盖内存层 + 输出三条流。
4. 旧 Leader 残留的未提交 delta 因 `activeMemberId` 不匹配而被共识层拒绝，不会污染状态。

#### 12.5 matchReplayMerge：撮合指令转发

```
  Raft log 提交
       │
       ▼ (全节点)
  matchOrderReq MDC publication → spy → Archive
                                         │
                            (仅 Leader)  │ replay
                                         ▼
                                  matchReplayMerge 线程
                                         │
                                  Aeron Cluster Client
                                         │
                                         ▼
                                    match-engine
```

- **Leader 独有**：只有 Leader 启动 matchReplayMerge 线程，从本地 matchOrderReq Archive 回放撮合指令，通过 Aeron Cluster Client Session 推送给 match-engine。
- **不反压共识状态机**：matchReplayMerge 与 Raft log 处理完全解耦。即使 match-engine 不可用或处理缓慢，共识状态机仍正常运行，matchOrderReq 持续写入 Archive 积累。
- **保证不丢数据**：matchOrderReq 先持久化到 Archive（由 Raft log 处理产出），再由 matchReplayMerge 异步转发。即使 Leader 崩溃，新 Leader 的 Archive 中有完整数据（全节点写入相同 Archive），新 Leader 的 matchReplayMerge 从上次发送位点继续。
- **去重**：match-engine 可通过 matchOrderReq 中的序号去重，防止 Leader 切换导致的重复发送。

#### 12.6 快照格式

trading-server 的 Aeron Cluster 快照需持久化以下状态：

| 消息 | 内容 | 说明 |
|------|------|------|
| TradingSnapshotHeader | `ringBufferCount`, `accountCount` | 快照头 |
| TradingAccountSnapshot | `uid`, 余额 / 冻结 / 持仓等全量字段 | 每个账户一条 |
| RingBufferMatchSeqSnapshot | `ringBufferIndex`, `matchSeq` | 每个 RingBuffer 的 matchSeq 位点 |

快照由 Aeron Cluster 内置机制管理（`onTakeSnapshot` / `onLoadSnapshot`），采用 SBE 编码。

#### 12.7 组件迁移映射

| 现有组件 (Kafka 架构) | 命运 | 新组件 / 替代方案 |
|------------------------|------|---------------------|
| `KafkaConsumer` (trading_{shard}) | 删除 | Aeron Cluster `onSessionMessage` |
| `KafkaProducer` (trading_result) | 删除 | tradingResult MDC + Archive |
| `KafkaProducer` (RESPONSE) | 删除 | response MDC + Archive |
| `Disruptor` / 4-slot 并行 | 删除 | RingBuffer[N] 分区 |
| `TradingLeaderElectionService` (ZK) | 删除 | Aeron Cluster 内置 Raft 选主 |
| `TradingResultSlaveFileQueue` (Chronicle) | 删除 | 全节点 Raft log + Archive |
| `TradingResultTailQueryService` | 删除 | matchSeq 位点 + ReplayMerge |
| `SettlementEventHandler` | 重构 | ClusteredService 的 `onSessionMessage` 处理 |
| `SlotContext` / `TradingAccount` | 保留 | 内存层 + 共识层各持有独立副本 |
| `CommandRouter` / Handler | 重构 | REST Controller → RingBuffer → Raft log |
| Spring Boot / REST | **保留** | 继续作为 open-api 接入层 |

## Risks / Trade-offs

- **[Risk] Aeron 运维经验需要积累**
  **Mitigation**: 先在 match-engine 侧验证 Aeron Cluster 运维模式（部署、升级、监控），积累经验后再推广到 trading-server。

- **[Risk] 单线程撮合在极端场景下可能成为瓶颈**
  **Mitigation**: 当前 5 万 TPS 在 SBE + 单线程能力范围内。若未来需要更高吞吐，可按 symbol group 拆分为多 Cluster，架构上已预留扩展点。

- **[Risk] 所有 trading-server shard 收到全量 MatchResult，过滤开销**
  **Mitigation**: SBE 解码极快（纳秒级读 shardId 字段），过滤开销可忽略。若 shard 数量极多且撮合量极大，可在 MDC 层面做 shard-aware 路由（如 MDC per shard），但当前不需要。

- **[Risk] match-engine 切主时 Archive 连续性**
  **Mitigation**: 所有节点（Leader + Follower）都维护完整的本地 Archive（因为都执行相同命令 → 确定性相同结果）。切主后消费者连接新 Leader 的 Archive，用 matchSeq 去重继续消费，无数据丢失。

- **[Risk] ReplayMerge 在高频场景下的追赶延迟**
  **Mitigation**: Archive 是本地磁盘 + 顺序读，replay 速率远高于实时写入速率；ReplayMerge 内部自动检测追赶完成并切换到 live，通常在秒级内完成。

- **[Trade-off] 双 MediaDriver 增加了部署复杂度**
  **Accepted**: 两个 MediaDriver 各自独立管理（Cluster 侧 + MDC 侧），但换来的是完全隔离——共识故障不影响输出录制，输出侧问题不影响 Raft 共识。全节点 Archive 一致，切主零感知。

- **[Trade-off] 严格 1:1 输入输出要求空 MatchResult 也必须输出**
  **Accepted**: 空 MatchResult 仅含 matchSeq + symbolId 头部字段，体积极小（< 30 bytes SBE）。换来的是 matchSeq 严格连续、间隙检测零歧义、对账直接比较数量。
