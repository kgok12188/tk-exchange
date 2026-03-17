## Context

本设计在不依赖现有 trading-server 代码的前提下，为 trading-server settlement 侧提供一套**从零可实现**的架构，实现：

- **唯一输入**：`trading_(shard)`（Kafka 单分区 Topic），消息统一格式 `{command, uid(可选), data}`。
- **唯一输出**：`trading_result_(shard)`（多分区 Topic，partition = slotIndex）。
- **结算内核**：以 `TradingSettle` 为唯一结算输入的状态机，更新用户级订单/持仓/账户/成交。
- **并行模型**：按 `uid` 做 slot 分片，单用户串行、多用户并行。
- **主从模型**：实例级主从（通过 Zookeeper/etcd 选主），仅主节点输出结果。

上游与下游整体链路为：

```
User / OpenAPI
    │ order / cancel / transfer
    ▼
message-dispatch / open_api / quote
    │ writes TradingSettle & other commands
    ▼
Kafka: trading_(shard)   (single partition)
    ▼
trading-server (this design, per shard)
    │ AsyncMessageItem...
    ▼
Kafka: trading_result_(shard)  (multi-partition: partition = slotIndex)
    ▼
trading_result_sync / risk / other downstreams
    ▼
DB & risk systems
```

## Goals / Non-Goals

### Goals

- 明确 trading-server settlement 的**输入输出边界**与消息格式。
- 设计一套 **slot + per-uid 状态机** 模型，在保证单用户事件顺序的前提下提升整体吞吐。
- 给出 `UserTradingBook`、`SettlementEngine`、`AsyncMessageItem` 等领域抽象，可直接据此实现。
- 兼容现有 `trading-protocol` 的 DTO 定义（`TradingSettle`、`FinishOrder`、`Ticket` 等）。
- 支持实例级主从，保证仅主节点对外写 `trading_result_(shard)`。

### Non-Goals

- 不规定具体持久化实现细节（仅约定输出事件格式，由 `trading_result_sync` 等服务落库）。
- 不覆盖撮合逻辑与 `order_req_(symbol)` / `match_result_(symbol)` 细节（由 match-engine 负责）。
- 不定义完整的 REST/gRPC API，只关注 MQ 协议与内部状态机。
- 不要求新实现与旧 trading-server 在类名/包名完全一致，允许在实现阶段做适配。

## High-Level Architecture

### 组件分层

```
┌──────────────────────────────────────────────┐
│              TradingServer (per shard)      │
├──────────────────────────────────────────────┤
│ 1. InboundConsumer                          │
│    - 单线程消费 Kafka trading_(shard)       │
│    - 解析 JSON {command, uid, data}         │
│    - 交给 CommandRouter                     │
│                                              │
│ 2. CommandRouter                            │
│    - 按 command 分类                        │
│    - 无 uid 指令：广播到所有 SlotQueue     │
│    - 有 uid 指令：根据 uid 选择单一 Slot   │
│                                              │
│ 3. SlotWorker[i] (i = 0 .. SLOTS-1)         │
│    - 独立线程 + 队列                        │
│    - 绑定 trading_result_(shard) partition i │
│    - 持有 SlotContext[i]                    │
│                                              │
│ 4. SlotContext[i]                           │
│    - Map<uid, UserTradingBook>              │
│    - SettlementEngine (处理 TradingSettle)  │
│    - UserCommandHandler (NEW_ORDER/...)     │
│                                              │
│ 5. ResultPublisher                          │
│    - 仅主节点启用                           │
│    - 将 AsyncMessageItem 写入对应 partition │
│                                              │
│ 6. HA & Replication                         │
│    - MasterElection (ZK/etcd)               │
│    - 从节点消费 trading_result_(shard) 对比 │
└──────────────────────────────────────────────┘
```

### Topic 与分区约定

- **输入 Topic**：`trading_(shard)`（单分区）。
- **输出 Topic**：`trading_result_(shard)`（多分区）。
  - 约定：slotIndex = i 的 SlotWorker **只写** partition i。
- Topic 命名建议与现有文档保持一致：如 `trading_message_01`、`trading_result_01`。

## Decisions

### 1. 统一输入格式：`{command, uid, data}`

- **Decision**：所有被 trading-server 消费的消息统一封装为 JSON：

```json
{
  "command": "XXX",
  "uid": 123456,
  "data": { }
}
```

- `command`：字符串枚举，标识指令类型：
  - 行情类：`UPDATE_MARK_PRICE`, `UPDATE_INDEX_PRICE`, ...
  - 用户类：`CREATE_USER`, `NEW_ORDER`, `CANCEL_ORDER`, `MATCH`, `TRANSFER`, ...
- `uid`：
  - 用户类指令：必填且 > 0，用于 slot 路由。
  - 行情/配置类：可为空或忽略。
- `data`：随 command 变化的 DTO，统一在 `trading-protocol` 模块定义。

**Rationale**：

- 路由层只依赖 `command + uid`，不依赖 `data` 内部结构，简化解耦。
- 易于在各语言/服务间保持一致的消息契约。

### 2. Slot 并行模型：per-uid 串行 + 跨用户并行

- **Decision**：为每个 shard 配置固定数量的 slot，按 `uid` 做 hash 路由：

- Slot 数量：
  - 配置项：`trading.shard.slot-count`（默认 = CPU 核数）。
  - 记为常量 `SLOTS`。

- 路由函数：

  ```text
  slot(uid) = abs(hash(uid)) % SLOTS
  ```

- 行为：
  - 有 `uid` 的指令 → 只投递到 `SlotQueue[slot(uid)]`。
  - 无 `uid` 的指令 → 广播到 `[0..SLOTS-1]` 所有槽位队列。
  - 每个 SlotWorker 是单线程，从自己的队列中按 FIFO 顺序消费消息。

**Rationale**：

- 保证「同一 uid 的所有指令」严格按 Kafka offset + 队列顺序串行执行。
- 允许不同用户在不同 slot 上并行处理，提高吞吐能力。

### 3. UserTradingBook 作为唯一内存真相来源（per user）

- **Decision**：在每个 slot 内，维护 `uid → UserTradingBook` 的映射，用于表示该 shard 上单个用户的完整交易状态。

- UserTradingBook 内容：
  - `orders: Map<OrderId, Order>`
  - `positions: Map<PositionId, Position>`
  - `accounts: Map<AccountId, Account>`
  - 变更缓冲（COW）：
    - `changeOrders`, `removeOrderIds`
    - `changePositions`, `removePositionIds`
    - `changeAccounts`, `removeAccountIds`

- 行为：
  - 查询 & 只读访问：`getOrder(...)` / `getPosition(...)` / `getAccount(...)`。
  - 修改入口：`getOrderToBuild` / `getPositionToBuild` / `getAccountToBuild` 返回可修改副本，并缓存在 change* 中。
  - 删除操作：通过 `remove*Ids` 标记。
  - 事务控制：
    - `commit()`：将所有 change* 与删除标记一次性合并到主 Map。
    - `rollback()`：丢弃 change* 与删除标记。

**Rationale**：

- 保证单个用户的订单/持仓/账户状态在一个聚合内完整可见。
- COW + commit/rollback 便于以「事务」视角处理每条消息。

### 4. SettlementEngine：以 TradingSettle 为唯一结算输入

- **Decision**：结算内核仅从 `TradingSettle`（和少量辅助指令）推导订单/持仓/账户变更，撮合细节全部由上游完成。

- 输入：单条 `TradingSettle{ uid, finishOrders[], tickets[] }`。
- 行为：
  1. 获取对应 uid 的 `UserTradingBook`。
  2. 对 `finishOrders`：
     - 根据 `orderId` 找到内存订单副本（`getOrderToBuild`）。
     - 将订单状态迁移到终态（COMPLETED / PART_CANCEL / CANCEL / EXCEPTION / POST_ONLY_REJECT 等）。
     - 根据 `leaveVolume` / `leaveAmount` 更新剩余量。
     - 释放或调整挂单冻结/保证金。
  3. 对 `tickets`：
     - 判断成交腿是开仓还是平仓：
       - 结合 `symbol`、side 与既有持仓方向/数量决策。
     - 更新持仓：
       - 调整 volume/closeVolume。
       - 维护开仓价、持仓成本、已实现盈亏。
     - 更新账户：
       - 按成交金额 price × volume 扣减/增加余额。
       - 处理手续费、资金费等（依赖配置）。
       - 在可用余额 / 冻结 / 保证金之间流转。
  4. 将这次事务涉及的订单/持仓/账户/成交变更收集为 `AsyncMessageItem[]`。
  5. 若全过程无异常 → `commit()`；否则 `rollback()`。

**Rationale**：

- TradingSettle 作为「撮合视角 → 用户视角」的最终接口，trading-server 可以完全独立演进结算逻辑。
- 明确划分撮合/结算职责，有利于多业务类型（现货、合约、期权）的扩展。

### 5. UserCommandHandler：处理非撮合型用户指令

- **Decision**：NEW_ORDER / CANCEL_ORDER / TRANSFER / CREATE_USER 等用户命令由独立的 CommandHandler 处理，仍通过同一 slot + UserTradingBook 模型更新状态。

- 示例行为：
  - `CREATE_USER`：
    - 根据 `data` 初始化 UserTradingBook 或从 DB 载入快照。
    - 输出 `AsyncMessageItem.USER_CREATED`。
  - `NEW_ORDER`：
    - 风控校验（保证金、杠杆、限额等）。
    - 在 UserTradingBook 中创建订单、冻结相应保证金。
    - 输出订单/账户变更的 AsyncMessageItem。
  - `CANCEL_ORDER`：
    - 校验订单可撤。
    - 变更状态为 CANCEL / PART_CANCEL，释放未成交冻结。
    - 输出相应 AsyncMessageItem。
  - `TRANSFER`：
    - 更新账户余额与资金流水。
    - 输出转账相关 AsyncMessageItem。

**Rationale**：

- 将非撮合型命令与 TradingSettle 统一纳入同一状态机和输出路径。
- 留出空间支持 open_api 直接写 trading_(shard) 的请求场景。

### 6. 统一输出：持久化批次（AsyncMessageItem） → trading_result_(shard)

- **Decision**：无论是 TradingSettle 还是非撮合型命令，所有状态机变更最终都以「持久化批次」的形式写入 `trading_result_(shard)`，具体由 `AsyncMessageItem` 承载：
  - `type`：标识本批次要写入的实体类型（ACCOUNT / ORDER / POSITION / TRADE / TRANSFER 等）。
  - `messages`：该类型下需要落库的一批记录（Account / Order / TradeOrder / Transfer 等）。

- AsyncMessageItem 视作一个 **PersistenceBatch** 抽象：

```text
AsyncMessageItem { // 持久化批次（PersistenceBatch）
  type: ENUM (ACCOUNT / ORDER / POSITION / TRADE / TRANSFER / ...), // 批次类型
  messages: List<Object>                                            // 同一类型下需要写库的一批记录
}
```

- 输出规则：
  - 一条输入消息在一个 slot 中处理完毕后，产生 0~N 条 AsyncMessageItem（每条代表一种实体类型的一批持久化记录）。
  - 当前 slotIndex = i 的 SlotWorker **只写入 partition i**。
  - `txid`（版本号）仍然由上层逻辑基于 `trading_(shard)` 的 offset 统一计算并注入到具体实体上（如 `account.txid`、`co_order.txid`、`trade_order.txid`、`transfer.txid`），用于表示「同一条输入消息」产出的所有数据库记录属于同一版本。

### 6.1 订单主键与分片的编码规则（order.id）

- **Decision**：在不支持批量下单（open-api 会将批量拆为多条单笔下单消息）的前提下，订单主键 `order.id` 直接来源于 `trading_(shard)` 的 Kafka offset 与分片 id 的组合：
  - 约定最多 **128 个逻辑分片**（shardId ∈ [0, 127]），每个分片对应一个 `trading_(shard)` 单分区 topic。
  - 使用 `long` 的 **低位 56 bit** 存储 Kafka offset（每个分片可支持 2^56 条消息，远超实际需要）。
  - 使用 `long` 的 **高位 7 bit** 存储分片 id（`shardId`），支持 0–127 共 128 个分片。
  - 编码形式概念上为：

  ```text
  orderId = (shardId << 56) | (offset & ((1L << 56) - 1))
  ```

  其中：
  - `offset` 为当前分片 topic（trading_(shard)）上本条消息的 Kafka offset；
  - `shardId` 为该分片的逻辑 id（配置或注册中心分配）。

- **Rationale**：
  - `order.id` 在全系统范围内天然唯一（不同分片 shardId 不同；同一分片 offset 唯一）。
  - 与 `txId = offset` 的定义兼容：`txId` 专注表示「该分片上的顺序位置」，`order.id` 则将「分片 + offset」编码为业务主键。
  - open-api 将批量下单拆为多条单笔下单消息后，每条消息都有自己的 offset，因此不需要在单一 offset 内生成多条订单 id。

**Rationale**：

- 将内部状态变化抽象为可重放的事件流，为对账、恢复和下游系统留出空间。
- 分区与 slot 一一对应，简化下游的消费与监控。

### 7. Master-only 输出与实例级主从

- **Decision**：trading-server 使用实例级主从模型：
  - 同一 shard 的多个实例通过 Zookeeper/etcd 选主。
  - **仅主实例**向 `trading_result_(shard)` 写入事件。
  - 从实例作为备用节点，保持状态机同步但不写输出。

- 主实例行为：
  - 消费 `trading_(shard)`。
  - 执行 slot + UserTradingBook 状态机。
  - 写入 `trading_result_(shard)`（partition = slotIndex）。

- 从实例行为：
  - 同样消费 `trading_(shard)`，使用同一状态机逻辑更新本地内存。
  - 可消费主节点的 `trading_result_(shard)` 进行抽样比对。
  - 不写任何输出。

**Rationale**：

- 保证外部系统只看到单一来源的 trading_result 事件，避免多写冲突。
- 备用实例可快速接管，故障切换时只需在有限窗口内做对账与补偿。

## Mapping to Persistence

本节简单说明 AsyncMessageItem 与现有表结构的对应关系（详细字段见 specs）。

- `ACCOUNT` → `account` 表。
- `ORDER` → `co_order` 表。
- `POSITION` → `co_position` 表。
- `TRADE` → `trade_order` 表。
- `TRANSFER` → `transfer` 表。

trading-server 不直接写库，而是通过 `trading_result_(shard)` 输出这些变更，由 `trading_result_sync` 统一落库。

## Risks / Trade-offs

- **[Risk] Slot 负载不均**  
  若用户分布不均匀，某些 slot 可能压力较大。  
  **Mitigation**：通过监控队列长度与处理延迟识别热点 slot；必要时提升 slot 数量或在业务维度引入更细粒度的分片（例如 user-group）。

- **[Risk] TradingSettle → 表结构映射复杂**  
  特别是在逐仓/全仓、不同合约类型下。  
  **Mitigation**：在 spec 中引入详细的状态迁移表，并通过端到端集成测试覆盖典型生命周期（开仓/加仓/减仓/平仓/爆仓/撤单等）。

- **[Risk] 主从结果比对成本**  
  从节点消费两路数据（trading_ 与 trading_result_）增加资源开销。  
  **Mitigation**：仅对最近窗口或抽样做比对；比对只输出指标与日志，不影响主路径。

