## Why

现有 trading-server 实现与最新的撮合/分发协议设计存在一定历史包袱：代码结构与职责划分受限于早期实现，难以完全对齐「OrderCommand → MatchResponse → TradingSettle → trading_result_(shard)」这条新链路。  
本变更希望在**不迁就旧实现**的前提下，为 trading-server settlement 侧提供一套从零可实现的架构和代码，实现：

- 以 `TradingSettle` 为唯一结算输入、以 `trading_result_(shard)` 为唯一输出的**状态机服务器**。
- 按 uid 分槽位（slot）的并行模型，保证同一用户事件串行、不同用户可并行。
- 与 `trading-protocol` 中的 DTO（`TradingSettle`、`FinishOrder`、`Ticket` 等）严格对齐。

目标是让任何新项目或重构场景，都可以直接复用本变更产出的 trading-server 作为结算内核，而无需依赖历史代码细节。

## What Changes

- **新增一个从零实现的 trading-server 结算服务**（可复用现有模块名 `tk-trading-server`，但以本变更的架构为准）：
  - 唯一输入：`trading_(shard)`（Kafka 单分区 topic）。
  - 唯一输出：`trading_result_(shard)`（多分区，partition = slotIndex）。
  - 统一消息格式：每条消息是一个 JSON，`{command, uid(可选), data}`。
  - 内部采用 **slot + per-uid 状态机**：
    - 单线程消费 `trading_(shard)`。
    - 根据 `command` 和 `uid` 路由到 N 个槽位队列。
    - 每个槽位一个工作线程 + 一个 `SlotContext`（包含 `Map<uid, UserTradingBook>`）。
  - `TradingSettle` 作为结算唯一输入：
    - SettlementEngine 负责将 `FinishOrder` + `Ticket` 映射为订单 / 持仓 / 账户 / 成交的状态变更。
    - 所有变更统一打包为 `AsyncMessageItem`（或等价结构）输出到 `trading_result_(shard)`。
  - 主从模型：
    - 实例级主从：通过 Zookeeper/etcd 选主。
    - 仅主节点写 `trading_result_(shard)`；从节点同样消费输入并执行状态机，但只做镜像与结果比对，不输出。

- **在 spec 中补充 trading-server settlement 的领域模型与状态机**：
  - `UserTradingBook`：聚合单用户的订单/持仓/账户状态及变更缓冲。
  - `SettlementEngine`：以 `TradingSettle` 为输入的结算状态机。
  - `AsyncMessageItem`：统一的结算输出事件模型，对齐持久化表结构。

- **给出现有数据库表结构映射关系**：
  - `account` / `co_order` / `co_position` / `trade_order` / `transfer` 等表与 `TradingSettle` 中 `FinishOrder`/`Ticket` 的对应关系。

本变更不强制替换或删除旧的 trading-server 代码，但会提供一套可并存、可迁移的全新实现蓝图和基础代码。

## Capabilities

### New Capabilities

- `trading-server-from-scratch`（结算内核）：
  - **唯一输入**：消费 `trading_(shard)` 中的 `TradingSettle` 及其它交易指令消息。
  - **slot + per-uid 状态机**：根据 `uid` 将请求路由到固定槽位队列，单用户串行、多用户并行。
  - **结算状态机**：
    - 以 `TradingSettle{ uid, finishOrders[], tickets[] }` 为输入。
    - 更新用户订单、持仓、账户与成交记录。
  - **统一输出**：将变更打包为 `AsyncMessageItem` 形式写入 `trading_result_(shard)`，供持久化与风控使用。
  - **主从热备**：仅主节点输出，备用节点消费主输出进行结果比对。

### Modified Capabilities

- `trading-server-settlement`：
  - 引入新的实现路径，明确「唯一输入/输出」与 slot 并行模型，作为现有 settlement 实现的演进方向或替代方案。

## Impact

- **代码结构**：
  - 在 `tk-trading-server` 模块内引入/重构为「入口消费层 + CommandRouter + SlotWorker + UserTradingBook + SettlementEngine + ResultPublisher」的分层结构。
  - 所有对外行为通过 `trading_(shard)` / `trading_result_(shard)` 与已有模块集成，减少对其它内部模块的耦合。

- **消息系统**：
  - 固定 trading-server 的消息契约：
    - 输入 topic：`trading_(shard)`（单分区）。
    - 输出 topic：`trading_result_(shard)`（多分区，partition = slotIndex）。
    - 消息格式统一为 `{command, uid(可选), data}`。
  - 与 `trading-protocol` 中的 `TradingSettle`、`FinishOrder`、`Ticket` 等 DTO 对齐。

- **数据库与持久化**：
  - 以 `trading_result_(shard)` 为唯一持久化入口，配合现有 `trading_result_sync` 服务写入：
    - `account` / `co_order` / `co_position` / `trade_order` / `transfer` 等表。
  - 通过 `txId` 等版本字段保证持久化有序与幂等。

- **运维与扩展性**：
  - 槽位数可配置，随 CPU 核数水平扩展。
  - 实例级主从模型简化部署，一份 shard 只需一主一从即可保障高可用。

