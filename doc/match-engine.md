# 撮合引擎（match-engine）

> 本节从 [README.md](../README.md) §3 独立，详述撮合引擎的设计与实现。

---

## 3.1 唯一输入：order_req_(币对)

- **所有进入撮合的数据**只来自 **orderReq_(币对)** 
- **每个币对**独立一个 topic，**严格顺序处理**：该币对下的消息单分区或单消费者顺序消费，保证同一币对内事件顺序一致。
- **输入与输出严格一对一**：每个币对对应一个 **orderReq_(币对)** 输入流、与该币对对应的撮合结果输出流（如 `match_result_(币对)`）一一对应；同一币对的订单只进该币对的 order_req，该币对的成交结果也只由消费该 order_req 的进程产出，不跨币对混合。

```
  币对 A:  orderReq_A  ──▶  [撮合 A]  ──▶  该币对撮合结果
  币对 B:  orderReq_B  ──▶  [撮合 B]  ──▶  该币对撮合结果
  …       一对一              一对一          一对一
```

## 3.2 相关 MQ Topic

- **orderReq_(币对)**：按币对的订单请求，撮合引擎的**唯一输入**；每个币对严格顺序处理。与**该币对的撮合结果输出**严格**一对一**（一输入流对应一输出流，不跨币对）。
- **match_result_(币对)**：按币对的撮合结果（输出），与 orderReq_(币对) 一一对应。

## 3.3 match-engine 槽位与 symbol 分片（概要）

- match-engine 进程内创建 **固定 N 个 slot**，每个 slot 负责 **多个币对**（`slotIndex = hash(symbol) % N`），同一 symbol 始终落在同一 slot，保证单币对顺序。每个 slot 独立 Kafka 消费、队列与撮合 worker，详见 **3.6**。

## 3.4 结果分发

- **match_result_(币对)** 会 **分发到** 用户分区（如 message_dispatch 按 uid 写入 trading_(分区)），即先按币对撮合，再按用户分区投递。

## 3.5 撮合流程与机制

本节描述撮合引擎内部的处理流程与核心机制（价格优先、时间优先、限价单与市价单行为），与 OpenSpec 变更 trading-server-match-engine 及协议一致。

### 3.5.1 撮合流程（单币对）

- **唯一输入**：`order_req_(币对)` 上的一条条 **OrderCommand**（PUSH_ORDER / CANCEL_ORDER），**严格按 Kafka 分区顺序**消费。
- **处理步骤**：
  1. 从 Kafka 拉取一条 order_req 消息，解析为 OrderCommand（含 type、pushPayload 或 cancelPayload）。
  2. **PUSH_ORDER**：根据 priceType 走 **LIMIT** / **MARKET** / **LIMIT_MAKER**（见 3.5.3～3.5.5）；产生 trades 与 finishOrders，若有则组装 MatchResponse。
  3. **CANCEL_ORDER**：从订单簿移除对应 orderId，产生一条 FinishOrder（终态 CANCEL），若有则组装 MatchResponse。
  4. 将 MatchResponse 序列化写入 **match_result_(币对)**，供 message_dispatch 消费并分发给 trading_(分区)。

```
  order_req_(币对)  (单分区，顺序消费)
         │
         ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  解析 OrderCommand                                            │
  │  PUSH_ORDER → 订单簿撮合/挂单   CANCEL_ORDER → 订单簿撤单      │
  └──────────────────────────────────────────────────────────────┘
         │
         ▼
  MatchResponse { taker, trades, finishOrders }  (若有成交或终态)
         │
         ▼
  match_result_(币对)  →  message_dispatch  →  trading_(分区)
```

### 3.5.2 订单簿与优先规则

- **订单簿结构**：每个币对维护一本内存订单簿，分为**买盘**与**卖盘**。
  - **买盘**：按价格**从高到低**排序（TreeMap），同价档内按**时间先后**排队（FIFO）。
  - **卖盘**：按价格**从低到高**排序（TreeMap），同价档内按**时间先后**排队（FIFO）。
  - **同价档实现**：同价档使用 LinkedHashMap（key=seq），插入顺序即时间优先；撮合时队首 peek，仅 maker 完全成交时按 seq 移除，撤单按 seq O(1) 移除。
- **时间优先的定义**：**时间优先 = order_req_(币对) 在 Kafka 分区上的排队顺序**。即：
  - 每个币对使用**单分区**（或单消费者顺序消费），保证同一币对下命令全序；
  - 引擎按 **offset 顺序** 处理命令，先处理的先挂单或先成交；
  - 同价档内先挂单的先被吃（FIFO），无需额外挂单时间戳；顺序由 Kafka 消息顺序唯一决定。

- **priceType 与行为对照**：

| priceType     | 入市时撮合 | 未成交部分   | 会交叉时           |
|---------------|------------|--------------|--------------------|
| **LIMIT**     | 是（先撮后挂） | 挂入订单簿   | 正常撮合           |
| **MARKET**    | 是（只撮不挂） | PART_CANCEL  | -                  |
| **LIMIT_MAKER** | 否         | 挂入订单簿   | 整单拒绝，POST_ONLY_REJECT |

### 3.5.3 限价单（LIMIT）逻辑

- **入市时先撮合、再挂簿**：
  - 买限价：从卖盘**最低价**开始，若卖价 ≤ 限价则成交，直到本单量用完或没有可成交价格；**未成交部分**挂入买盘该限价档的**队尾**。
  - 卖限价：从买盘**最高价**开始，若买价 ≥ 限价则成交，直到本单量用完或没有可成交价格；**未成交部分**挂入卖盘该限价档的**队尾**。
- **成交价**：与对手盘成交时，使用**对手盘挂单价格**（maker 价格）。
- **输出**：每笔成交生成一条 TradeOrder；若某订单完全成交或进入终态，生成一条 FinishOrder（COMPLETED / PART_CANCEL 等）。

### 3.5.4 限价只做 Maker（LIMIT_MAKER / post-only）逻辑

- **只挂簿、不主动吃单**：订单必须以 maker 身份挂在盘口，**不允许**在入市时与现有盘口成交（即不能成为 taker）。
- **入市检查**：
  - **买 LIMIT_MAKER**：若买价 ≥ 当前卖一价（会立即成交），则**整单拒绝**，不挂入订单簿、不产生任何成交；返回一条 FinishOrder，终态为 **POST_ONLY_REJECT**（或等价拒绝状态），leaveVolume = 原委托量。
  - **卖 LIMIT_MAKER**：若卖价 ≤ 当前买一价（会立即成交），则**整单拒绝**，不挂入订单簿、不产生任何成交；同上返回 POST_ONLY_REJECT。
- **通过检查时**：与普通限价单一样，**仅挂入**对应买卖盘限价档队尾，不入市时不做任何撮合；后续若被对手单吃掉，则以 maker 身份成交。
- **典型用途**：做市、避免 taker 手续费、保证挂单方始终为 maker。

### 3.5.5 市价单（MARKET）逻辑

- **只撮合、不挂簿**：从对手盘最优价开始依次吃单，直到本单量用完或对手盘空。
- **未成交部分**：不再挂入订单簿，以 **PART_CANCEL** 终态输出一条 FinishOrder；若全部成交则输出 COMPLETED。
- **价格**：每笔成交使用当时对手盘档位价格，无本单限价约束。

### 3.5.6 状态机视角

- 订单在撮合侧的状态可抽象为：**挂单中**（在订单簿）→ **部分成交** / **完全成交** / **已撤单**。
- **PUSH_ORDER** 驱动：新单入市、可能立即产生多笔成交与多条 FinishOrder（maker/taker 终态）。
- **CANCEL_ORDER** 驱动：从订单簿移除该单，产生一条 FinishOrder（CANCEL，剩余量 leaveVolume/leaveAmount）。
- 撮合引擎**不直接写库、不操作账户/持仓**，只维护订单簿并产出 MatchResponse；结算与持久化由 trading-server 消费 trading_(分区) 后完成。

---

## 3.6 match-engine 内部并行模型（多 ringBuffer + symbol hash）

前面从「单币对视角」描述了撮合流程。本小节从 **match-engine 进程内部** 的角度，说明在保证单币对严格顺序的前提下，如何利用多线程 / 多 ringBuffer 提升整体吞吐。

### 3.6.1 设计目标

- **单币对视角**：
  - 保证同一 `symbol` 的所有 `OrderCommand` 严格按 `order_req_(symbol)` 的 Kafka offset 顺序处理。
  - 保证价格优先、时间优先（同价档 FIFO）在并行场景下不被破坏。
- **整体视角**：
  - 当币对数量较多时，允许不同币对在不同工作线程上并行撮合，充分利用 CPU 核心。

### 3.6.2 symbol → slot → worker 映射（每 slot 独立 consumer）

match-engine 进程内部预先创建固定数量的 **slot**，每个 slot **独立拥有** Kafka consumer、队列与撮合 worker：

- 定义 `slotIndex = hash(symbol) % N`，同一 symbol 始终落在同一 slot。
- **每个 slot**：
  - 负责一组 `order_req_(symbol)` topics（满足 `hash(symbol)%N == slotIndex`）；
  - 独立 **Kafka consumer**，启动时 `assign(本 slot 的 TopicPartition 列表)`，无中心 consumer；
  - **消费线程**：poll → 解析 symbol → 入本 slot 队列；
  - **worker 线程**：take → 按 symbol 路由到本 slot 内的 MatchEngine（订单簿），撮合后写 match_result_(symbol)。
- 配置：`match.symbols`（初始币对列表）、`match.ringBufferNumbers` 或 `match.slots`（N）。

结构示意：

```
  order_req_(BTC_USDT)   order_req_(ETH_USDT)   order_req_(DOGE_USDT)  ...
         │                       │                       │
         └───────────┬───────────┴───────────┬───────────┘
                     │  slot = hash(symbol) % N
                     ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │                        match-engine                              │
  │  slot 0: consumer0(assign topics) → queue0 → worker0 → enginesBySymbol  │
  │  slot 1: consumer1(assign topics) → queue1 → worker1 → enginesBySymbol  │
  │  ...                                                             │
  │  slot N-1: consumerN-1 → queueN-1 → workerN-1 → enginesBySymbol  │
  └─────────────────────────────────────────────────────────────────┘
```

### 3.6.3 单币对顺序与时间优先

在上述模型下，仍然满足本章前面定义的「时间优先 = Kafka 排队顺序」：

- **同一 symbol 映射到唯一 slot**：
  - 同一 `symbol` 的所有 `OrderCommand` 通过 `hash(symbol)` 保证始终落在同一个 `slot` / worker 上。
- **slot 内串行处理**：
  - 每个 worker 线程按队列顺序依次读取 `(symbol, OrderCommand)`，对该 symbol 对应的订单簿做撮合。
  - 对于同一 symbol 而言，所有命令在该 worker 内是严格 FIFO，顺序与 `order_req_(symbol)` 的 Kafka offset 一致。
- **同价档 FIFO**：
  - 每个 symbol 的订单簿使用「价格有序（TreeMap）+ 同价档队列」：
    - 买盘：价格从高到低，同价档 FIFO。
    - 卖盘：价格从低到高，同价档 FIFO。
  - **同价档实现**（与代码一致）：同价档使用 **LinkedHashMap&lt;Long, BookOrder&gt;**（key=seq），插入顺序即时间顺序；队首用 peekFirst，**仅当 maker 完全成交**时按 seq 从档位移除（remove(seq)），撤单也按 seq O(1) 移除。
  - 同一 symbol 只在一个 worker 内被修改，顺序由 Kafka 消息顺序 + worker 队列顺序唯一决定。

因此，对任意给定的币对：

> 时间优先 = `order_req_(symbol)` 在 Kafka 分区上的 offset 顺序  
> ＋ 该 symbol 在所属 worker 队列中的处理顺序。

worker / ringBuffer 带来的并行性**仅发生在不同 symbol 之间**，不会改变**单一 symbol** 内的撮合顺序与价格/时间优先规则。

### 3.6.4 与 trading-server 分片模型的对应关系

- **trading-server**：按 `uid` 分片（`userSlot(uid)`），每个 slot 对应一个 `RingBufferTradingBook` 线程，负责一组用户的账户 / 订单 / 持仓结算。→ 详见 [trading-server 详细设计](trading-server.md)
- **match-engine**：按 `symbol` 分片（`hash(symbol)`），每个 slot 对应一个撮合线程，负责一组币对的订单簿与撮合。

整体上，系统在两个正交维度上做水平扩展：

- **用户维度**：在 trading-server 侧按 uid 分片，独立处理结算状态机。
- **币对维度**：在 match-engine 侧按 symbol 分片，独立处理撮合订单簿。

这样既保证了单用户、单币对视角下的严格顺序与一致性，又允许多个用户 / 多个币对在不同线程上并行处理，以提升整体吞吐。

### 3.6.5 运行时上币与 slot 事件队列（pendingSlotEvents）

- **语义**：每个 MatchSlot 维护 **pendingSlotEvents**（待处理 slot 事件队列），用于**监听是否需要上币**与**监听主从切换**。事件类型：
  - **ADD_SYMBOL(symbol)**：需要增加该币对（上币）。
  - **BECAME_MASTER**：本实例已切换为主节点（用于触发补发或切换写 Kafka）；由 **`MatchManager.notifyBecameMaster()`** 统一投递（ZK `isLeader()` 或无 ZK 单机主启动）。
  - **BECAME_SLAVE**：本实例已切换为从节点（用于停止写 Kafka、改为写文件队列；从节点需消费 match_result_* 同步 masterOffset，见 3.6.7）；由 **`notifyBecameSlave()`** 投递。
- **上币流程**：入口仍为 `MatchManager.addSymbol(symbol)`（由配置或管理接口触发）；根据 `slotIndex = hash(symbol) % N` 找到对应 slot，向该 slot 的 **pendingSlotEvents** 投递 **ADD_SYMBOL(symbol)**。slot 的**消费线程**（consumeLoop）在每轮 poll 前 drain pendingSlotEvents：遇到 ADD_SYMBOL 则创建 MatchEngine、将 `order_req_(symbol)` 加入当前 assign 集合并 `assign(更新后的 TopicPartition 列表)`，此后该 topic 的消息进入本 slot 队列，由 worker 按 symbol 路由到对应 MatchEngine。运行时上币时，若当前为从节点，`MatchManager.addSymbol` 还会向 **MatchResultMasterFileQueue** 投递该 symbol，以便从节点订阅 `match_result_(symbol)` 同步主进度。
- **切主/切从流程**：见 3.6.7「主从与从节点消费 match_result_*」。
- **前提**：仅 match-engine 侧逻辑；对应的 Kafka topic（如 `order_req_BTC_USDT`）需已存在或由上游创建。

### 3.6.6 订单簿快照与启动恢复

快照用于 **启动恢复**：进程重启后从共享磁盘加载快照，从快照对应的 order_req offset 继续消费，避免从 0 回放。

**存储与文件名**

- **存储**：共享磁盘目录，由配置 **`match.snapshot-dir`** 指定；为空或未配置时禁用快照与恢复；所有 slot 写入同一目录。
- **文件名**：`{symbol}.{19位offset，前缀补0}`，例如 `BTC_USDT.0000000000000123456`。最后一个 `.` 前为币对，后为 order_req **已处理到的** offset（即 last processed offset）；约定币对名称中不含 `.`。

**快照文件内容与写入**

- **第 1 行**：快照元数据（单行 JSON，`SnapshotMetadata`）：`offset`、`orderCount`、`symbol`、`ts`。
- **第 2 行起**：每行一个 **BookOrder** 的 JSON（直接序列化内存中的 `BookOrder`），字段含：orderId、uid、shardId、side、price、remainingVolume、volume、seq、sideBuy；市价 IOC 相关字段 `amount` / `remainingAmount`（quote）若存在可一并序列化；**金额字段**（price、remainingVolume、volume 等）以 **plain string** 形式写入（`BigDecimal.toPlainString()`），避免精度与科学计数法问题；字符集 **UTF-8**。
- **写入方式**：使用 **流式写入**（`BufferedWriter`），按行追加，最后 `flush()`，不将整份文件内容放入内存；文件编码 UTF-8。

**加载与校验（load）**

- **候选文件**：在快照目录下匹配 `{symbol}.{19位数字}` 的文件，按 **offset 降序**排列（最新快照在前）。
- **多候选回退**：依次尝试候选 0、1、2…；若当前候选校验或解析失败则尝试下一个，**第一个完全通过校验的候选**作为加载结果；若全部失败则返回 null（不恢复）。
- **单文件校验**：对每个候选执行：
  - 文件为**普通文件且可读**（`Files.isRegularFile`、`Files.isReadable`）；
  - 文件非空；
  - 第 1 行可解析为 `SnapshotMetadata`，且 **metadata 合法**：offset ≥ 0、orderCount ≥ 0、symbol 非空且与请求的 symbol 一致；
  - **订单行数** = `lines.size() - 1` 等于 metadata 的 `orderCount`；
  - **文件名中的 offset** 与 metadata 的 `offset` 一致；
  - 每一行订单：非空、可反序列化为 `BookOrder`，且 **订单合法**（price、remainingVolume 非空，remainingVolume ≥ 0）。
- 通过校验后：将每行反序列化为 `BookOrder`，按 **seq 升序排序**，依次 **restoreOrder** 重建 OrderBook（仅挂单、不撮合）。

**恢复流程（MatchSlot 启动）**

1. 对每个 symbol，调用 `SnapshotFileHelper.load(snapshotDir, symbol)`，得到 `SnapshotLoadResult(offset, orders)` 或 null。
2. 若加载成功：用 `getOrderBook(engine, loaded)` 将订单列表 restoreOrder 进 OrderBook，设置 `book.setReqOffset(loaded.offset())`，并记录该 symbol 的 seek 目标为 **`loaded.offset() + 1`**。
3. consumer **assign** 本 slot 的 TopicPartition 后，对存在快照的 symbol 执行 **`seek(partition, loaded.offset() + 1)`**：因快照中的 offset 表示「已处理到的」offset，下一次 poll 应从 **下一条** 消息开始，避免重复处理最后一条已处理消息。
4. 无快照或 load 返回 null 的 symbol 从当前默认位点（如 earliest）消费。

**打快照触发**

- **配置**：`match.snapshot-enabled`（是否启用定时快照）、`match.snapshot-dir`（目录，与启用一起生效）、`match.snapshot-interval-ms`（间隔毫秒，默认 300000）；另有 `match.snapshot-interval-minutes`（分钟，优先级低于 interval-ms）。
- **定时任务**：`SnapshotScheduleService` 使用 **`@Scheduled(fixedDelayString = "${match.snapshot-interval-ms:300000}", initialDelay = 60_000)`**，即首次延迟 60 秒后按间隔执行；仅当 `snapshotEnabled && snapshotDir 非空` 时执行。
- **流程**：对每个 slot 调用 `MatchManager.getSymbolsBySlotIndex(i)` 得到该 slot 当前 symbol 集合，对每个 symbol 调用 `MatchManager.submitSnapshotRequest(symbol)`；请求投递到对应 slot 的 **queue**（与 order_req 同队，`SlotTask.snapshot(symbol)`），由 worker 按序执行。
- **写盘**：worker 执行 `takeSnapshot(symbol)` 时，取 `book.getReqOffset()`，通过 `book.visitBookOrder(...)` 逐个遍历挂单写入快照，调用 `SnapshotFileHelper.write(snapshotDir, symbol, offset, book, ...)`（内部先计数再写行，避免一次性 `exportOrders()` 分配整表集合）；一致点为当前已处理的 order_req offset。

### 3.6.7 match-engine 主从与高可用（设计约定）

主从在 **Java 实例维度**（非币对维度）：ZK 在多个 match-engine 实例间选出一个 **leader**，整实例为主，其余为从；同一实例内所有 slot/币对共用该实例的主或从角色。

**多实例消费同一批 order_req_**

- 主、从均消费**同一批** `order_req_(symbol)`（如通过两套 consumer group 或两路订阅），实现热备：从与主做相同撮合，仅输出目的地不同。

**输出与文件队列**

- **主**：撮合结果写入 **Kafka**（`match_result_(币对)`），下游（如 message_dispatch）只消费 Kafka。
- **从**：撮合结果写入**文件队列**（本地），不写 Kafka；从的消费/处理速度可以**快于**主，文件队列中会多出「主尚未写到 Kafka」的 MatchResponse。

**从节点文件队列实现约定（每币一个队列）**

- **目录布局**：配置项 **`match.file-queue-dir`** 为根目录（baseDir）。实现上在根目录下分 **`slave/`** 与 **`master/`** 两支，再按 symbol 各建子目录；每个 symbol 对应一个独立的 Chronicle Queue（一个目录即一个 queue），例如 **`{file-queue-dir}/slave/BTC_USDT`**、**`{file-queue-dir}/master/BTC_USDT`**。同一 slot 内多币对即多个 queue，互不共用。
  - **`slave/{symbol}/`**：从节点撮合产出的 MatchResponse 写入此处（`MatchResultSlaveFileQueue`），供升主补发。
  - **`master/{symbol}/`**：从节点消费 Kafka `match_result_*` 后写入的主侧副本（`MatchResultMasterFileQueue`），用于更新 `masterOffset` 及与 slave 抽样比对。
- **进程启动**：`MatchManager` 构造阶段若 `match.file-queue-dir` 非空，会**递归删除整个根目录**（含 `master/`、`slave/` 下已有 Chronicle 数据），冷启动不沿用旧文件；若需持久化保留，请使用独立路径或另行调整代码。
- **写入时机与接入点**：在 **MatchSlot.process()** 中，当 `response != null` 且当前实例为**从**时，不调用 `producer.send`，改为向该 symbol 对应的文件队列追加一条记录；主节点保持现有逻辑（producer.send + 成功回调更新 masterOffset）。
- **记录格式**：与 **ChronicleQueueTest#replayFromOffsetSimulation** 一致，便于补发时用同一套读逻辑。每条记录 = **orderReqOffset**（long，即 order_req 的 Kafka offset）+ **payload**（String，即 MatchResponse JSON）。Chronicle Wire 写法：`wire().write().int64(orderReqOffset).write().text(payload)`。
- **补发读法**：升主后对每个 symbol 的 queue 创建 tailer，顺序读每条记录；仅将 **orderReqOffset > 该 symbol 当前 masterOffset** 的 payload 按序发往 Kafka，发送成功后调用 **updateMasterOffsetIfGreater(orderReqOffset)** 更新 masterOffset；补发完成后清空该 symbol 的文件队列，再切换为该 symbol 的主逻辑（新产生的 response 直接 producer.send）。与测试用例中的「replayFromOffsetSimulation」逻辑一致。

**OrderBook#masterOffset 的维护**

- **主节点**：通过 **Kafka producer 推送 match_result_ 的成功回调**更新本实例各 symbol 对应 OrderBook 的 **masterOffset**。语义：`masterOffset` = 本实例已成功写入 match_result_ 的 order_req 进度（orderReqOffset）。
- **从节点**：通过 **MatchResultMasterFileQueue** 消费主节点写入的 **match_result_***，每收到一条消息解析出 symbol 与 orderReqOffset，调用 `MatchManager.updateMasterOffset(symbol, orderReqOffset)` → 对应 slot 的 `OrderBook.updateMasterOffsetIfGreater(orderReqOffset)`，**仅当 orderReqOffset 大于当前 masterOffset 时更新**，避免回退。语义：从上的 `masterOffset` = 主已写入 match_result_ 的 order_req 进度。从仅用此消费更新 masterOffset，不改变从的订单簿状态（从的订单簿由 order_req 驱动）。
- **MatchResultMasterFileQueue 的启动与订阅**：**仅从节点启动**该队列的消费线程。在 **MatchManager.notifyBecameSlave()** 中先 **ensureStarted()**（若未启动则启动消费线程），再对各 slot 的 symbol 调用 **addSymbol(symbol)** 动态订阅 `match_result_(symbol)`；**notifyBecameMaster()** 中调用 **matchResultMasterFileQueue.stop()** 停止消费。主节点不消费 match_result_*。

**故障切换与补发**

- 从晋升为主后，需将文件队列中 **order_req offset > 当前 masterOffset** 的 MatchResponse **按序补发**到 Kafka，再继续以主身份写 Kafka，保证下游看到连续、有序的 match_result_ 流。
- 补发范围以 Kafka 当前进度（或 masterOffset）为界，避免重复与漏发。

**监听是否切换成主节点**

- **数据面主从仅以 MatchSlot 为准**：每个 slot 内部 `isMaster` 决定 `process()` 写 Kafka 还是写 slave 文件队列；**已移除**全局静态 `HaStatus`，避免与撮合线程状态不一致。
- **MatchManager#anyMaster()**：当**至少一个** `MatchSlot` 的 `isMaster()` 为 true 时返回 true（OR 语义）；用于快照调度、一致性校验等粗粒度判断。**非**「全部 slot 均已切主」；若需全为主需对每个 slot 单独判断。
- **ZK 模式**：`MatchLeaderElectionService` 在 Curator `LeaderLatch` 回调 **`isLeader()`** 中调用 **`MatchManager.notifyBecameMaster()`**；在 **`notLeader()`** 中调用 **`notifyBecameSlave()`**。上述方法向**每个** MatchSlot 的 **pendingSlotEvents** 投递 **BECAME_MASTER** / **BECAME_SLAVE**，由 consumeLoop 转发至 Disruptor，在处理 **HA** 事件时执行文件队列补发并切换 `MatchSlot.isMaster`。
- **无 ZK（单机/开发）**：未配置 `match.zookeeper-servers` 时选举不启用，启动时直接 **`notifyBecameMaster()`**，实例按**单机主**运行（与 slot 内补发路径一致）。

**主从文件队列抽样比对（状态机一致性校验）**

- **目的**：验证从节点自身产出的 MatchResponse（baseDir/slave）与从 Kafka 消费到的主节点产出（baseDir/master）在相同 orderReqOffset 下内容一致，从而校验主从状态机数据一致。
- **方式**：抽样比对，仅比对**最新一段**数据；采用**以 orderReqOffset 对齐**（方式 A）：
  - 分别从 slave、master 两条队列取**最后一条**记录的 orderReqOffset，记为 lastSlave、lastMaster。
  - **对齐点**：`end = min(lastSlave, lastMaster)`（两边都有的最大 orderReqOffset）。
  - **倒推 N 条**：在区间 `(end - N, end]` 内，对每个 orderReqOffset 在两条队列中取对应 record，逐条比较 payload。
  - 若某 orderReqOffset 仅在一侧存在，可记缺失并打 error；若两侧都有但 payload 不一致，打 **error** 日志（含 symbol、orderReqOffset、差异摘要），便于人工介入和排查。
- **行为**：仅读两条队列、比对、输出日志或指标；不修改 Chronicle 队列文件。抽样**全部一致**时可通过 `MatchManager.updateComparedProgressFromConsistencyCheck` 更新 OrderBook 对齐进度；主从角色仍由选主与 slot HA 决定。调度侧仅当 **`MatchManager.anyMaster()` 为 false**（没有任何 slot 为主，通常即整实例为从）时执行比对（`MatchResultChecker`）；切主过程中若已存在任一 slot 为主则本轮跳过比对。
- **并发与数据来源（约定）**：每个 symbol 的 **OrderBook** 仅在 **MatchSlot worker** 单线程路径上读写与变更；**禁止**多线程并发修改同一 **OrderBook**。主从一致性抽样比对**仅针对已落盘**的 **`{file-queue-dir}/slave/{symbol}`** 与 **`{file-queue-dir}/master/{symbol}`** Chronicle 队列记录，**不在**比对路径上直接对比两份内存 **OrderBook**；比对任务只读磁盘队列，与撮合热路径解耦，**不应**与 worker 对同一簿产生并发争用。

---

## 3.7 能力

- **多 Topic 定序**：MQ 提供多 Topic 的定序能力。
- **延迟**：数据延迟在 1ms 以内。

## 3.8 主要 Topic（与 §2 一致）

| Topic | 说明 | 分区 |
|-------|------|-----|
| **trading_(分区)** | trading-server **唯一输入**；写入来源：message_dispatch（成交）、open_api（下单）、行情（标记价/指数价） | 按分区 |
| **trading_result_(分区)** | 交易结果（主节点写入）；**该 topic 有多个 partition**，**每个 ringBuffer（每个 trading-book 槽位）分别输出到各自对应的 partition**。与 trading_(分区) 一一对应（同一逻辑分区）。**从节点也消费**，用于确定主节点在 trading_(分区) 的位点并进行结果比对 | 多 partition |
| **response** | 请求响应（仅主节点**按需**写入） | - |
| **quote** | 标记价格 / 指数价格（行情写入；也可写入 trading_(分区) 供状态机消费） | - |
| **manager** | 管理类消息 | - |
| **match_result_(币对)** | 撮合成交结果（由 message_dispatch 消费后分发到 trading_(分区)） | 按币对 |
| **orderReq_(币对)** | 撮合引擎**唯一输入**，按币对的订单请求；每个币对严格顺序处理 | 按币对 |

- **约定**：**match_result_(币对)** 与 **order_req_(币对)** 每个 topic **仅一个分区**（partition 0）。实现与设计时按单分区处理，不考虑多分区、rebalance 或按分区聚合。
- 所有进入 trading-server 的请求与事件均经 **trading_(分区)** 进入。
