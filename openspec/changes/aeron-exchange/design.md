## Context

本设计描述交易系统基于 Aeron 的完整架构，覆盖 match-engine（指令共识）和 trading-server（结果共识）两侧。

```
open-api
→ trading-server (结果共识: 内存层 + 共识层 + 输出层)
    输出层三条 MDC 流:
    · matchOrderReq → match-engine (指令共识, Aeron Cluster)
    · response → open-api
    · tradingResult → flush-service → MySQL
match-engine (Aeron Cluster, 单线程, 严格 1:1)
→ Aeron MDC + Spy Archive: MatchResult (matchSeq)
→ trading-server (ReplayMerge 消费)
→ 行情 service (ReplayMerge 消费)
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

### 7. Spy 录制 + 本地 Archive + 扩展录制

- **Decision**: 每个 Cluster 节点（Leader 和 Follower）将 MatchResult 写入本地 IPC publication，由 Aeron Archive 通过 **spy subscription** 零拷贝录制。Leader 额外写 MDC publication 做网络发送。
- **双 publication 模型**:
  - `localPub` (IPC): 所有节点 offer，供本地 Archive spy 录制。
  - `mdcPub` (UDP MDC): 仅 Leader offer，网络发送给订阅者。
- **扩展录制 (extend recording)**:
  - 使用 `AeronArchive.extendRecording()` 确保 `recordingId` 在重启后保持不变。
  - 消费者可以用固定的 recordingId 发起 ReplayMerge，不受 match-engine 重启影响。
- **启动去重**:
  - 启动时若已有 Archive 录制，先本地 replay 找到 `lastRecordedMatchSeq`。
  - 设 `nextMatchSeq = lastRecordedMatchSeq + 1`。
  - Cluster 回放 Raft log 期间，产出的 matchSeq ≤ lastRecordedMatchSeq 的 MatchResult 跳过写入 Archive，避免重复。
  - matchSeq > lastRecordedMatchSeq 的正常写入。
- **matchSeq → Archive position 索引**:
  - 内部维护 `TreeMap<Long, Long>` — key=matchSeq, value=archive recording position。
  - 启动时从 Archive replay 重建，运行时每次 offer 后更新。
  - 用途：消费者指定 matchSeq 查询对应 archive position，从该位置发起 ReplayMerge。
- **ClusteredService 伪逻辑**:
  ```
  long nextMatchSeq = 0
  long lastRecordedMatchSeq = -1

  onStart():
      if (archive has existing recording):
          replay → find lastRecordedMatchSeq
          nextMatchSeq = lastRecordedMatchSeq + 1

  onSessionMessage(session, buffer):
      cmd = decode(buffer)
      result = engines.get(cmd.symbolId).process(cmd)
      if (result == null):
          result = MatchResult.empty(cmd.symbolId)

      result.matchSeq = nextMatchSeq

      if (nextMatchSeq > lastRecordedMatchSeq):
          pos = localPub.offer(encode(result))   // 所有节点 → spy
          matchSeqIndex.put(nextMatchSeq, pos)
          if (cluster.role() == LEADER):
              mdcPub.offer(encode(result))        // Leader → 网络

      nextMatchSeq++
  ```
- **Spy subscription**: `aeron-spy:aeron:ipc` — 从 driver send buffer 直接读取，零拷贝，不影响 publication 性能。Archive 独立运行，与撮合线程零耦合。
- **Rationale**:
  - 所有节点维护完整 Archive（因为所有节点处理相同命令 → 确定性相同结果 → Archive 内容一致），切主时新 Leader 的 Archive 已有完整历史。
  - Spy 录制与撮合线程解耦：match-engine 的 ClusteredService 不需要知道 Archive 存在。
  - 扩展录制保证 recordingId 稳定，消费者无需感知 match-engine 重启。
  - matchSeq 索引支持按业务序号精确定位 Archive 位置。

### 8. ReplayMerge：消费者的统一入口

- **Decision**: trading-server 和行情 service 通过 Aeron Archive 的 **ReplayMerge** 消费 match-engine 输出。ReplayMerge 自动处理断线追赶和实时消费的无缝衔接。
- **工作原理**:
  1. 消费者记录上次处理到的 `lastProcessedMatchSeq` 和对应的 `archivePosition`。
  2. 创建 ReplayMerge，连接 match-engine 节点的 Archive（replay 通道）和 MDC（live 通道）。
  3. ReplayMerge 从 archivePosition 开始 replay，自动与 live MDC 合并。
  4. 消费者始终使用同一个 `replayMerge.poll(handler, fragmentLimit)` 接口，无需区分阶段。
  5. 消费者用 matchSeq 做间隙检测：收到 matchSeq N 后下一条必须是 N+1，否则告警。
- **match-engine 切主时的消费者行为**:
  - 因为所有节点维护相同的 Archive（见 §7），消费者可连接任意节点的 Archive。
  - 检测到 live MDC 中断后，重建 ReplayMerge，连接新 Leader 的 Archive 和 MDC。
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

- **Decision**: trading-server 采用**结果共识**，架构分为三层：**内存层**、**共识层（Raft log）**、**输出层**。所有输出通过 Aeron MDC + 本地 Archive 录制。
- **为什么不用指令共识**:
  - trading-server 处理 NEW_ORDER 时有外部副作用：发送 order_req 给 match-engine、发送 response 给 open-api、写 trading_result 给 flush-service。
  - 如果所有副本都执行命令，会产生重复副作用（match-engine 收到 3 份重复订单、用户收到 3 份响应等）。
  - 结果共识：只有 Leader 接收外部请求并处理，将结果写入 Raft log。全节点处理 Raft log 更新内存层和输出三条流。
- **核心模型**:
  - 内存层与共识层数据隔离，各自维护独立的数据副本。
  - Leader：接收请求 → 在内存层 RingBuffer 中处理（不查询共识层）→ 变更推入 Raft log → 共识层更新 + 输出三条流。
  - Follower：Raft log → 共识层数据更新 → 通知内存层更新 + 输出三条流。
  - Leader 独有 matchReplayMerge 线程：从本地 matchOrderReq Archive 回放 → Aeron Cluster Client → match-engine。

#### 12.1 内存层：RingBuffer 分区

- 全量 account 数据驻留内存，按用户划分到不同的 **RingBuffer**。
- 每个 RingBuffer 负责一组用户（`ringBuffer = userSlot(uid)`），持有该组用户的账户余额、订单、持仓、冻结等全量状态。
- **内存层与共识层数据隔离**：两层各自维护独立的数据副本，读写路径不交叉。
- **Leader**：接收请求和撮合结果，路由到对应 RingBuffer，在内存层中直接处理（校验/冻结/结算），**不查询共识层数据**。处理完毕后将变更推入 Raft log。
- **Follower**：不接收请求、不消费撮合结果。共识层数据更新后通知内存层更新。
- RingBuffer 提供 shard 内的并行处理能力，同一用户的操作在同一 RingBuffer 中串行，保证单用户顺序性。

#### 12.2 共识层：独立数据副本 + Raft log

共识层维护自己独立的数据副本，通过 Raft log 与 Leader 的变更同步。

- **共识层元数据**：
  - `ringBufferCount`：RingBuffer 数量，运行期间不可变更。
  - `ringBufferMatchSeq[i]`：每个 RingBuffer 已处理的 MatchResult 的 matchSeq 位点，随业务变更一起提交。
  - 全量 account 数据副本。
- **Leader → 共识层**：Leader 在内存层处理完毕后，将变更（delta + 对应 RingBuffer 的 matchSeq 位点）通过 Raft log 推送给共识层。Leader 处理过程中不依赖共识层数据，处理性能不受共识延迟影响。
- **Follower 数据流**：Raft log → 共识层数据更新 → 通知内存层更新。Follower 的内存层完全由共识层驱动。
- **输出三条流**：全节点在 Raft log 提交后输出三条 MDC 流到本地 Archive，Leader 额外通过 MDC 网络发送。
- 全节点 Archive 内容一致（处理相同 Raft log → 确定性相同输出）。

#### 12.2.1 切主流程

- 新 Leader 当选后，向共识层推送一条**切主消息**（LeaderChange），通过 Raft log 提交。
- 切主消息被处理后，确认 Raft log 中排在它之前的所有条目都已被共识层处理完毕，共识层的 `ringBufferMatchSeq` 是完整、一致的。
- 从共识层获取所有 RingBuffer 的 matchSeq 位点，取 `min(ringBufferMatchSeq)` 作为消费起点。
- 从该 matchSeq 开始通过 ReplayMerge 消费 MatchResult：
  - matchSeq ≤ 某 RingBuffer 的已处理位点 → 该 RingBuffer 跳过（已处理）。
  - matchSeq > 某 RingBuffer 的已处理位点 → 正常处理。
- **为什么取最小值**：不同 RingBuffer 处理不同用户的 MatchResult，各自进度可能不同。取最小值确保不丢失任何 RingBuffer 尚未处理的数据，已处理的通过 matchSeq 比较跳过。
- **RingBuffer 数量不可变**：共识层记录 ringBufferCount，运行期间不允许变更，保证 Leader 和 Follower 的分区映射一致。

#### 12.3 输出层：三条 MDC 流 + 本地录制

输出层采用与 match-engine 相同的模式（MDC + Spy → Archive），分为三条独立的流。**全节点**在处理 Raft log 时输出。

| 流 | 内容 | 消费者 | 用途 |
|-----|------|--------|------|
| **matchOrderReq** | PushOrderCommand / CancelOrderCommand | match-engine（通过 matchReplayMerge 转发） | 撮合指令 |
| **response** | 订单响应（接受/拒绝/状态更新） | open-api | 响应用户请求 |
| **tradingResult** | 结算结果（账户变更、订单更新、成交记录） | flush-service | 持久化到 MySQL |

每条流的架构：
- `localPub` (IPC)：全节点 offer，Spy → 本地 Archive 录制。
- `mdcPub` (UDP MDC, Dynamic)：仅 Leader offer，网络发送给消费者。
- 扩展录制（extend recording）：确保 recordingId 跨重启稳定。
- 全节点 Archive 一致，切主后消费者可连接新 Leader 的 Archive。
- 消费者通过 ReplayMerge 订阅，支持断线追赶 + 实时消费。

#### 12.4 matchReplayMerge：撮合指令转发线程

- **Decision**: Leader 启动独立的 matchReplayMerge 线程，从本地 matchOrderReq Archive 回放撮合指令，通过 Aeron Cluster Client Session 推送给 match-engine。
- **不反压共识状态机**：matchReplayMerge 与 Raft log 处理完全解耦。即使 match-engine 不可用或处理缓慢，共识状态机仍正常运行，matchOrderReq 持续写入 Archive 积累。
- **保证不丢数据**：matchOrderReq 先持久化到 Archive（由 Raft log 处理产出），再由 matchReplayMerge 异步转发。即使 Leader 崩溃，新 Leader 的 Archive 中也有完整数据（Follower 也写了相同的 Archive），新 Leader 的 matchReplayMerge 从上次发送位置继续。
- **去重**：match-engine 可通过 matchOrderReq 中的序号去重，防止 Leader 切换导致的重复发送。
- **Rationale**:
  - 解耦：trading-server 共识处理速度不受 match-engine 处理速度影响。
  - 可靠性：Archive 提供持久化保证，matchReplayMerge 提供可靠交付。
  - HA：全节点 Archive 一致，切主无缝衔接。

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

- **[Trade-off] 双 publication（localPub + mdcPub）增加了 Leader 的 offer 次数**
  **Accepted**: IPC offer 是纯内存操作，开销可忽略。换来的是所有节点 Archive 一致，切主零感知。

- **[Trade-off] 严格 1:1 输入输出要求空 MatchResult 也必须输出**
  **Accepted**: 空 MatchResult 仅含 matchSeq + symbolId 头部字段，体积极小（< 30 bytes SBE）。换来的是 matchSeq 严格连续、间隙检测零歧义、对账直接比较数量。
