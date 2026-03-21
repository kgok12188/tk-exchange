## Context

本设计在不受现有代码约束的前提下，为三大服务提供一套从零可实现的技术方案：

- **match-engine**：按币对消费 `order_req_(symbol)`，维护订单簿并产出 `MatchResponse`。
- **message-dispatch**：消费 `MatchResponse`，按 uid 拆分并生成 per-user 的 `TradingSettle`，写入 `trading_(分区)`。
- **trading-server**：以 `TradingSettle` 为唯一输入，驱动用户账户/订单/持仓状态机，并输出 `trading_result_(分区)`。

整体链路：

```
User / OpenAPI
    │  order / cancel
    ▼
trading-server (front)
    │  OrderCommand: PUSH_ORDER / CANCEL_ORDER
    ▼
Kafka: order_req_(symbol)
    ▼
match-engine (per symbol)
    │  MatchResponse { taker, trades, finishOrders }
    ▼
Kafka: match_result_(symbol)
    ▼
message-dispatch
    │  TradingSettle{uid, FinishOrders, Tickets}
    ▼
Kafka: trading_(shard by uid)
    ▼
trading-server (settlement)
    │  AsyncMessageItem → trading_result_(shard)
    ▼
持久化 / 风控 / 其他下游
```

## Goals / Non-Goals

**Goals:**

- 明确三大服务之间的**协议边界**（Command / Response / Settle）并给出字段级约定。
- 为 match-engine 设计一套可复用的撮合内核接口与订单簿模型。
- 为 message-dispatch 设计基于 uid 的拆分与路由逻辑，将撮合结果映射到用户视角的结算事件。
- 为 trading-server 设计基于 TradingSettle 的结算状态机与输出模型，对应到订单、持仓、账户与成交表结构。
- 使上述设计足够独立，可在重构或新项目中直接实现，而不依赖当前仓库细节。

**Non-Goals:**

- 不约束具体的持久化技术栈（关系型数据库、KV、事件存储等由实现决定）。
- 不指定具体的撮合算法细节（如价格优先、时间优先的实现细节由 match-engine 内部负责）。
- 不设计外部 REST/gRPC API 的全部接口，只关注服务间 MQ 协议。
- 不强制要求与当前项目中的类名完全一致，允许在实现阶段再做适配。

## Decisions

### 1. 采用三层解耦架构：撮合引擎 / 分发层 / 结算引擎

- **Decision**：将撮合（match-engine）、分发（message-dispatch）、结算（trading-server）三个角色完全解耦，通过明确的消息协议衔接。
- **Rationale**：
  - 撮合逻辑与账户/持仓的结算逻辑天然关注点不同，解耦后更易演进。
  - 分发层（message-dispatch）专注于「撮合视角 → 用户视角」的映射，便于实现按 uid 分区、按用户维度扩展。
  - 交易服务器可以聚焦于状态机与一致性，不必考虑撮合细节。

### 2. 统一的请求协议：`OrderCommand` + `order_req_(symbol)` Topic

- **Decision**：trading-server 向 match-engine 的唯一指令形式为 `OrderCommand`，承载在 `order_req_(symbol)` topic 上，指令类型仅：
  - `PUSH_ORDER`：推送新订单。
  - `CANCEL_ORDER`：撤销已有订单。
- **Rationale**：
  - 将「行为」与「载荷」分离，避免使用多 topic 或隐含字段来区分指令。
  - 方便 match-engine 在不理解上游实现的情况下，统一处理指令。
  - **协议自洽**：仅凭本协议（OrderCommand、MatchResponse、DTO 字段约定）即可实现撮合引擎，无需参考现有代码或业务实现细节。
- **关键字段（PUSH_ORDER）**：
  - `id`, `uid`, `symbol` / `marketId`, `side`, `priceType`, `price`, `volumeOrAmount`。
  - **priceType** 取值：`LIMIT`（限价，先撮合再挂簿）、`MARKET`（市价，只撮合不挂簿）、`LIMIT_MAKER`（post-only，只挂簿不撮合；若入市时会与盘口交叉则整单拒绝，返回 FinishOrder 终态如 POST_ONLY_REJECT，不挂簿、不成交）。
- **关键字段（CANCEL_ORDER）**：
  - `orderId`（必填），`uid`（可选，用于安全校验/路由）。

### 3. 撮合结果协议：`MatchResponse`

- **Decision**：match-engine 不直接对用户/账户表做任何操作，只产出纯粹的撮合结果 `MatchResponse`：
  - `taker`：触发本笔撮合/撤单的一方（可为 null）。
  - `trades`：`List<TradeOrder>`，每条记录包含 taker/maker 与买卖两条腿。
  - `finishOrders`：`List<FinishOrder>`，包含所有进入终态的订单及剩余未成交量。
- **Rationale**：
  - 撮合服务对状态只负责「价格与配对」，不关心资金/仓位变动，职责单一。
  - 下游可以基于统一协议实现多种结算方式（现货、合约、期权等）。
- **撮合引擎状态机**：撮合内核可建模为状态机——订单状态包括「挂单中」「部分成交」「完全成交」「已撤单」等；`PUSH_ORDER` / `CANCEL_ORDER` 驱动状态迁移，`MatchResponse` 为每次迁移产生的事件输出。实现时仅需依据本设计中的协议与 spec，无需依赖现有代码即可完成撮合引擎功能。

### 4. TradeOrder 设计：显式表示买卖双方与 taker/maker

- **Decision**：不使用单腿 `uid + orderId + role` 的形式，而是：
  - `index, orderReqOffset, price, volume`（协议字段可为 matchId，语义即 order_req offset）：
    - `takerUid, makerUid`
    - `buyUid, sellUid`
    - `buyOrderId, sellOrderId`
    - `takerOrderId`
- **Rationale**：
  - 一条 TradeOrder 就能完全描述一次撮合事件的双方身份和角色，避免下游再推演。
  - message-dispatch 可以更简单地按 uid 拆解为 Ticket。

### 5. 用户视角的结算事件：`TradingSettle` + `Ticket`

- **Decision**：在 message-dispatch 中引入 per-user 的结算包：
  - `TradingSettle{ uid, List<FinishOrder>, List<Ticket> }`。
  - `Ticket{ index, orderReqOffset, price, volume, uid, orderId, isTaker }`（协议中 orderReqOffset 可为 matchId）。
- **Rationale**：
  - 将 match-engine 的「全局视角」转化为 trading-server 的「用户视角」。
  - trading-server 只需要处理本用户的 FinishOrders + Tickets，就能更新账户/仓位/订单。
  - 便于做 per-user 分区（`uid -> shard`），实现 settlement 层的水平扩展。

### 6. trading-server 作为状态机：以 TradingSettle 为唯一输入

- **Decision**：trading-server 的 settlement 模块以 `TradingSettle` 为唯一外部输入：
  - 内部维护 `UserTradingBook`（账户/订单/持仓状态）。
  - 处理完一个 TradingSettle 后，将变更封装为 `AsyncMessageItem` 输出到 `trading_result_(shard)`。
- **Rationale**：
  - 输入单一、幂等性好，可以自然按 uid 或 shard 划分并发。
  - 输出作为「真相来源」驱动持久化与风控，便于热备与对账。

### 7. match-engine 内部并行模型（多 slot + symbol hash）

- **Decision**：match-engine 进程内创建固定 N 个 **slot**，每个 slot 独立拥有 Kafka consumer、队列与撮合 worker；`slotIndex = hash(symbol) % N`，同一 symbol 始终落在同一 slot，保证单币对严格顺序。
- **每个 slot**：维护**币对列表**（symbols），topic 由 **`order_req_(symbol)`** 推导（即 `order_req_` + symbol）；负责的 topics 满足 `hash(symbol)%N == slotIndex`；独立 consumer 使用 `assign(本 slot 的 TopicPartition 列表)`；消费线程 poll → 入队；worker 线程 take → 按 symbol 路由到本 slot 内的 MatchEngine（订单簿），撮合后写 `match_result_(symbol)`。配置项：`match.symbols`（初始币对）、`match.ringBufferNumbers`（N）。
- **运行时上币与 slot 事件队列**：每个 slot 维护 **pendingSlotEvents**（待处理 slot 事件队列），事件类型包括：**ADD_SYMBOL(symbol)**（需上币）、**BECAME_MASTER**（已切换为主节点）。消费线程（consumeLoop）在每轮 poll 前 drain 该队列，按事件类型处理：ADD_SYMBOL → 创建 MatchEngine、将 `order_req_(symbol)` 加入 assign 集合并 assign；BECAME_MASTER → 执行切主后动作（如触发补发或仅依赖下次 process 写 Kafka）。上币入口：`MatchManager.addSymbol(symbol)` 根据 slotIndex 找到对应 slot，向该 slot 的 pendingSlotEvents 投递 **ADD_SYMBOL(symbol)**；配置刷新、管理接口或未来「上币 topic」也可向同一队列投递 ADD_SYMBOL，实现「监听是否需要上币」。
- **与代码对应**：MatchManager（编排、上币、构建 N 个 MatchSlot）；MatchSlot（consumer + queue + worker，enginesBySymbol，**pendingSlotEvents**，**addSymbol(symbol)** 内部投递 ADD_SYMBOL，getSymbols()）；MatchEngine + OrderBook（单 symbol 订单簿）；PriceLevel（同价档 LinkedHashMap key=seq，peekFirst / addLast / remove(seq)）。

### 8. 订单簿快照与启动恢复

- **Decision**：快照用于**启动恢复**；存储于**共享磁盘**目录，由配置 **`match.snapshot-dir`** 指定；为空时禁用快照与恢复。文件名 `{symbol}.{19位offset，前缀补0}`（如 `BTC_USDT.0000000000000123456`），约定币对名称中不含 `.`；offset 表示 **已处理到的** order_req 消息 offset。
- **快照内容**：第 1 行为快照元数据（单行 JSON，SnapshotMetadata）：`offset`、`orderCount`、`symbol`、`ts`。第 2 行起每行一个 **BookOrder** 的 JSON（与内存结构一致，含 orderId, uid, shardId, side, price, remainingVolume, volume, seq, sideBuy）；金额字段以 **plain string** 存储（BigDecimal.toPlainString），字符集 **UTF-8**。空订单簿时仅第 1 行且 `orderCount=0`。
- **写入方式**：**流式写入**（BufferedWriter），按行追加，UTF-8，最后 flush，不将整份文件内容放入内存。
- **加载与校验**：在快照目录下匹配 `{symbol}.{19位数字}` 的候选文件，按 offset **降序**排列；**依次尝试**候选 0、1、2…，对每个候选做**有效性校验**（文件可读、非空、metadata 合法、订单行数=orderCount、文件名 offset 与 metadata 一致、每行订单可解析且合法）；第一个全部通过的候选作为加载结果；反序列化后按 **seq 升序排序**，依次 **restoreOrder** 重建 OrderBook。
- **恢复与 seek**：设置 OrderBook 的 reqOffset = 快照 offset；consumer 对 `order_req_(symbol)` **seek(offset + 1)** 后继续消费（因快照 offset 为「已处理到的」，下一次应从下一条消息开始，避免重复处理）。
- **打快照触发**：配置 `match.snapshot-enabled`、`match.snapshot-dir`、`match.snapshot-interval-ms`（默认 300000）；定时任务（如 fixedDelay，initialDelay 60s）调用 `MatchManager.getSymbolsBySlotIndex(slotIndex)` 获取各 slot 币对，对每个 symbol 调用 `MatchManager.submitSnapshotRequest(symbol)`；请求投递到对应 slot 的 queue（与 order_req 同队），由 worker 按序执行，写盘时使用当前已处理的 order_req offset 作为一致点。
- **与代码的对应**：MatchManager 暴露 `getSymbolsBySlotIndex(int)`、`submitSnapshotRequest(String symbol)`；MatchSlot 支持 SnapshotTask 入队及 worker 分支 `takeSnapshot(symbol)` 写盘；SnapshotFileHelper 负责 write（流式/UTF-8）、load（多候选+校验）、PlainStringBigDecimalSerializer；OrderBook 支持 exportOrders()、**restoreOrder(...)** 仅挂入买卖盘不撮合、getReqOffset/setReqOffset。

### 9. match-engine 主从与高可用

- **Decision**：match-engine 支持主从热备，主从在 **Java 实例维度**（非币对维度）；使用 **Zookeeper** 在多个 match-engine 实例间选主；主节点将撮合结果写入 Kafka，从节点写入本地**文件队列**；从可消费更快，切换时需**补发**文件队列中未在 Kafka 的数据。
- **多实例消费同一批 order_req_**：主、从均消费同一批 `order_req_(symbol)`（如两套 consumer group），热备同输入；主写 Kafka，从写文件队列。
- **OrderBook#masterOffset**：
  - **主**：通过 **producer 推送 match_result_ 的成功回调**更新本实例各 symbol 的 OrderBook.masterOffset，表示「已成功写入 Kafka 的 order_req 进度（orderReqOffset）」。
  - **从**：**消费同一组 match_result_**（主写的 topic），解析每条消息的 orderReqOffset，按 symbol 更新本实例 OrderBook.masterOffset，**实时感知主的处理速度**；从不用 match_result_ 更新订单簿，仅更新 masterOffset。
- **补发**：从晋升为主后，将文件队列中 **order_req offset > masterOffset** 的 MatchResponse 按序补发到 Kafka，再继续写 Kafka，保证下游看到连续流；补发边界以 Kafka 当前进度（masterOffset）为准，避免重复与漏发。
- **监听是否切换成主节点**：**已移除**全局 `HaStatus`；数据面以 **MatchSlot.isMaster** 为准；**`MatchManager.anyMaster()`** 表示是否存在**至少一个** slot 已切主（OR），供定时任务粗判，**不是**「全部 slot 已切主」。ZK 模式下 `LeaderLatch` 回调 **`isLeader()`** / **`notLeader()`** 分别调用 **`notifyBecameMaster()`** / **`notifyBecameSlave()`**，向每个 MatchSlot 的 **pendingSlotEvents** 投递 **BECAME_MASTER** / **BECAME_SLAVE**；无 ZK 时启动即 **`notifyBecameMaster()`**（单机主）。consumeLoop 将 HA 转发至 Disruptor，处理补发并切换 `isMaster`。
- **从节点文件队列（每币一个队列）**：
  - **每币一个队列**：配置 **`match.file-queue-dir`**（从节点文件队列根目录）；在该目录下按 **symbol 建子目录**，每个 symbol 一个 Chronicle Queue（一个目录即一个 queue），例如 `{file-queue-dir}/BTC_USDT`。
  - **写入时机**：在 **MatchSlot.process()** 中，当 `response != null` 且当前实例为从时，不 producer.send，改为向该 symbol 的文件队列追加一条记录。
  - **记录格式**：与 **ChronicleQueueTest#replayFromOffsetSimulation** 一致：每条 = **orderReqOffset**（long，order_req 的 Kafka offset）+ **payload**（String，MatchResponse JSON）。Chronicle Wire：`wire().write().int64(orderReqOffset).write().text(payload)`。补发时 tailer 顺序读，仅发 orderReqOffset > masterOffset 的 payload。
  - **参考**：测试类 `com.tk.match.queue.ChronicleQueueTest#replayFromOffsetSimulation`。
- **主从文件队列抽样比对（状态机一致性校验）**：
  - **目的**：验证从节点自身产出的 MatchResponse（baseDir/slave）与从 Kafka 消费到的主节点产出（baseDir/master）在相同 orderReqOffset 下内容一致，从而校验主从状态机数据一致。
  - **方式**：抽样比对，仅比对**最新一段**数据；**以 orderReqOffset 对齐**（方式 A）：
    - 分别从 slave、master 两条队列取**最后一条**记录的 orderReqOffset，记为 lastSlave、lastMaster。
    - **对齐点**：`end = min(lastSlave, lastMaster)`（两边都有的最大 orderReqOffset）。
    - **倒推 N 条**：在区间 `(end - N, end]` 内，对每个 orderReqOffset 在两条队列中取对应 record，逐条比较 payload。
    - 若某 orderReqOffset 仅在一侧存在，可记缺失并打 error；若两侧都有但 payload 不一致，打 **error** 日志（含 symbol、orderReqOffset、差异摘要），便于人工介入和排查。
  - **行为**：仅读两条队列、比对、输出日志或指标；不修改 Chronicle 队列文件；抽样全部一致时可 `updateComparedProgressFromConsistencyCheck` 更新 OrderBook；主从角色仍由选主与 slot HA 决定。定时任务仅在 **`MatchManager.anyMaster()` 为 false** 时跑比对（`MatchResultChecker`）；任一条 slot 为主则跳过本轮。
- **Rationale**：与 trading-server 主从模型一致（实例级 + ZK）；从写文件队列降低对 Kafka 的依赖并保留可补发缓冲；masterOffset 统一「主已对外可见进度」的语义；每币一队列 + 统一格式便于实现与补发对齐；抽样比对在不影响主路径的前提下提供一致性校验与人工排查入口。

## Risks / Trade-offs

- **[Risk] 协议较为复杂，字段多，版本演进成本高**  
  **Mitigation**：在 `trading-protocol` 模块中集中维护 DTO 与版本号（如 header 中加入 `schemaVersion`），通过向后兼容字段扩展而不是破坏性修改。

- **[Risk] 三层解耦带来多跳 MQ，延迟叠加**  
  **Mitigation**：在设计实现时尽量做到「撮合 → 拆分 → 结算」链路无阻塞处理；对大部分场景可以接受的延迟换取扩展性；必要时支持撮合与结算共址部署减少网络开销。

- **[Risk] TradingSettle 与 Account/Order/Position 之间的映射逻辑复杂**  
  **Mitigation**：在 `trading-server-settlement` spec 中详细定义各表字段与业务含义，对每种 Ticket / FinishOrder 组合给出状态迁移表，并通过集成测试验证。

- **[Risk] match-engine 与 message-dispatch 之间的一致性问题**  
  **Mitigation**：以 Kafka offset 为基础做 at-least-once 处理，并依靠 `index + orderReqOffset`（即 order_req offset，协议中可为 matchId）做幂等；必要时在 TradingSettle 中携带幂等 key。

