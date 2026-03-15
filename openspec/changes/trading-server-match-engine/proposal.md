## Why

交易链路目前在文档中已经抽象出了统一的协议（`order_req_(币对)` → `MatchResponse` → `TradingSettle` → `trading_result_(分区)`），但在代码层面还没有一套与之完全对齐、从零即可复用的实现蓝图。  
本变更希望在**不受现有代码约束**的前提下，为 `trading-server`、`match-engine`、`message-dispatch` 三个核心服务提供一套统一的协议与编码设计，为后续重构或全新实现提供标准参考。

**撮合引擎采用状态机架构**：订单在订单簿中的生命周期（挂单 → 部分成交 → 完全成交/撤单）由明确的状态迁移描述；实现时仅需依据本变更定义的协议（`OrderCommand` / `MatchResponse` 等）即可完成撮合引擎的开发，**无需参考现有代码**。

## What Changes

- **定义统一的消息协议层**：抽象出 `OrderCommand`、`MatchResponse`、`TradingSettle`、`Ticket`、`FinishOrder` 等 DTO，作为三大服务之间的稳定契约。
- **为 match-engine 设计独立的撮合内核**：
  - 按 `order_req_(symbol)` 单一输入，支持 `PUSH_ORDER` / `CANCEL_ORDER` 指令。
  - **状态机架构**：订单簿与订单状态（挂单、部分成交、完全成交、撤单）由状态迁移定义；撮合逻辑仅依赖协议 DTO，可不参考现有代码实现。
  - 基于订单簿与最新成交价生成 `MatchResponse`（包含 `trades` 与 `finishOrders`）。
  - **多 slot 并行**：进程内固定 N 个 slot，`slotIndex = hash(symbol) % N`，每 slot 独立 consumer/队列/worker，支持运行时上币（addSymbol/addTopic）。
  - **订单簿快照与启动恢复**：快照存共享磁盘，文件名 `{symbol}.{19位offset}`，内容首行描述（offset、orderCount 等）、后续每行一单 JSON；恢复时按 seq 排序重建订单簿，consumer 从快照 offset 继续消费；定时任务通过 getSymbolsBySlotIndex + submitSnapshotRequest 触发打快照。
- **为 message-dispatch 设计用户视角的结算拆分层**：
  - 消费 `MatchResponse`，按 uid 将撮合结果拆分为 per-user 的 `TradingSettle`。
  - 生成 `Ticket`（含 `uid`、`order_id`、`is_taker` 等），并路由到对应 `trading_(分区)`。
- **为 trading-server 设计基于 TradingSettle 的结算状态机**：
  - 将 `TradingSettle` 作为唯一输入，驱动用户级账户、订单、持仓状态机。
  - 输出 `trading_result_(分区)` 作为持久化与风控的统一数据源。
- **对现有架构文档进行规范化补充**：在第四章中对交易协议与持久化数据结构给出与上述设计一致的描述（包括 topic 命名、字段约定、顺序与幂等语义）。

## Capabilities

### New Capabilities

- `trading-protocol`: 定义撮合与结算链路上的通用消息协议（OrderCommand、MatchResponse、TradingSettle、Ticket、FinishOrder 等），作为多服务协作的稳定契约。
- `match-engine-core`: 基于 `order_req_(symbol)` 与统一协议实现的撮合内核与订单簿管理能力，支持 PUSH / CANCEL 指令和撮合结果输出；多 slot 并行（per-slot consumer/queue/worker，symbol hash）；订单簿快照与启动恢复（共享磁盘、定时触发、按 seq 恢复）。
- `message-dispatch-routing`: 负责消费 `MatchResponse` 并按 uid 拆分生成 `TradingSettle` 的分发与路由能力，将撮合结果映射到 `trading_(分区)`。
- `trading-server-settlement`: 以 `TradingSettle` 为输入驱动用户账户/订单/持仓的结算逻辑，并产出 `trading_result_(分区)` 的结算状态机能力。

### Modified Capabilities

- `<none>`: 当前变更主要在于新增一套自洽的交易链路协议与实现蓝图，不直接修改已有 specs 中的对外功能性要求；后续若需要与现有模块对齐，可在对应 capability 下补充 delta spec。

## Impact

- **代码结构**：
  - 新增独立的协议定义模块（例如 `trading-protocol`），被 `match-engine`、`message-dispatch`、`trading-server` 共同依赖。
  - 为三大服务补充新的内部边界与接口设计（不强制要求立刻替换现有实现）。
- **消息系统**：
  - 进一步规范 `order_req_(币对)`、`match_result_(币对)`、`trading_(分区)`、`trading_result_(分区)` 等 topic 的语义与载荷。
- **数据库与持久化**：
  - 以 `trade_order`、`co_order`、`co_position`、`account` 等表结构为参考，对结算输出（`trading_result_(分区)`）的字段和语义进行对齐。
- **后续演进**：
  - 为重构现有撮合/结算链路，或在新项目中复用这套架构，提供可直接落地的 spec 与设计基础。

