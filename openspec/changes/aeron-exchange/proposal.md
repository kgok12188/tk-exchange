## Why

当前交易系统基于 Kafka 做服务间通信，端到端延迟在毫秒级（撮合→结算通常 4-10ms）。对于期货/合约交易场景，Kafka 引入的延迟、运维复杂度（broker 集群 + ZooKeeper 选主 + Chronicle Queue HA + 多 slot 线程模型）已成为瓶颈。

本变更将核心交易链路从 Kafka 迁移到 **Aeron**（Aeron Cluster + Aeron Archive + MDC），目标是：

- **延迟**：核心路径（撮合→结算）从毫秒级降低到微秒级。
- **简化 HA**：用 Aeron Cluster 内置 Raft 共识替代 ZooKeeper 选主 + Chronicle Queue 主从文件队列。
- **简化架构**：删除 message-dispatch 服务，减少一跳；删除多 slot 线程模型，match-engine 改为 Aeron Cluster 单线程撮合。
- **序列化**：JSON (fastjson2) → SBE (Simple Binary Encoding)，零拷贝、零 GC。

## What Changes

### match-engine：Aeron Cluster + 指令共识

- 撮合引擎改为 **Aeron Cluster** 部署（3 节点），使用 **指令共识**：所有节点处理相同的有序命令流，维护相同的 OrderBook 状态。
- **单线程撮合**：删除多 slot 并行模型（MatchSlot / MatchManager / pendingSlotEvents），所有 symbol 在 `ClusteredService.onSessionMessage()` 单线程中处理。
- **MDC 出口**：撮合结果通过 Aeron MDC (Multi-Destination-Cast, Dynamic control-mode) 发布，所有 trading-server shard 和行情服务订阅。
- **Spy 录制**：所有节点将结果写入本地 IPC publication，由 Aeron Archive 通过 spy subscription 零拷贝录制，不与撮合线程耦合。
- **ReplayMerge**：消费者（trading-server、行情）通过 Aeron Archive 的 ReplayMerge 实现断线追赶 + 实时消费的无缝衔接。
- **Cluster 快照**：替代当前自定义快照文件格式（SnapshotFileHelper），使用 Aeron Cluster 内置的 `onTakeSnapshot` / `onLoadSnapshot`。

### trading-server：结果共识（待设计）

- trading-server 从指令共识改为 **结果共识**：仅 Leader 执行命令并产生副作用，结果复制给 Follower。
- 具体设计后续讨论。

### 协议层：JSON → SBE

- `trading-protocol` 模块新增 SBE schema 定义。
- 核心消息：`PushOrderCommand`、`CancelOrderCommand`、`UpdateMarketCommand`、`MatchResult`。
- BigDecimal → Decimal64 (mantissa int64 + exponent int8)。
- String symbol → symbolId (uint32)。

### 删除的组件

- **message-dispatch 模块**：uid 扇出逻辑移入 trading-server 内部。
- **ZooKeeper 依赖**（match-engine 侧）：由 Aeron Cluster Raft 替代。
- **Chronicle Queue 依赖**：由 Aeron Archive 替代。
- **Kafka 依赖**（核心交易路径）：由 Aeron 替代。

## Capabilities

### New Capabilities

- `aeron-match-engine`: 基于 Aeron Cluster 的撮合引擎，指令共识，单线程撮合，MDC 出口 + Spy Archive 录制。
- `aeron-sbe-protocol`: 基于 SBE 的交易协议编解码，替代 JSON ProtocolSerde。

### Modified Capabilities

- `match-engine-core`: 核心撮合逻辑（MatchEngine + OrderBook）不变，外层调度模型完全替换。
- `trading-protocol`: 新增 SBE schema，保留 DTO 定义作为参考。

### Removed Capabilities

- `message-dispatch-routing`: 删除独立 message-dispatch 服务。

## Impact

- **代码结构**：match-engine 模块大幅简化（删除 MatchSlot / MatchManager / HA / Chronicle / Snapshot 等 10+ 个类）；新增 ClusteredService 实现和 SBE codecs。
- **部署**：match-engine 从单进程 + ZK 改为 3 节点 Aeron Cluster；删除 Kafka broker 和 ZooKeeper 对 match-engine 的依赖。
- **消息系统**：核心路径不再依赖 Kafka；外围服务（open-api REST、flush-service）的接入方式需适配。
- **后续演进**：trading-server 结果共识设计、行情服务 Aeron 接入、open-api 网关层适配。
