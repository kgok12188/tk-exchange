# trading-server：单一输入、分区与主从模型

> 本节从 [README.md](../README.md) §2 独立，详述 trading-server 的核心约定与设计。

---

## 2.1 单一输入与约定

- trading-server 的**唯一输入源**为 **trading_(分区)**（Kafka topic 命名统一用下划线，如 `trading_message_(分区)`，与配置统一即可）。
- **trading_(分区) 与 trading_result_(分区) 对应关系**：每个逻辑分区有一条输入流 **trading_(分区)**、一条输出 **trading_result_(分区)**（同一 topic）。**trading_result_(分区) 有多个 Kafka partition**；**每个 ringBuffer（每个 trading-book 槽位）分别输出到该 topic 下各自对应的 partition**（槽位 i → partition i），互不混用。
- **response** 与 **orderReq_(币对)** 为**按需发送**：有请求响应或订单需进入撮合时才写，非固定流。
- **写入 trading_(分区) 的来源**（即谁往该 Topic 投递消息）：
  - **message_dispatch**：分发的**成交信息**（撮合结果按用户分区写入对应 trading 分区）；
  - **open_api**：**用户下单数据**（下单/撤单等请求）；
  - **行情服务**：**标记价格**、**指数价格** 推送。

## 2.2 主从热备与从节点

- **trading-server 角色**：
  - 是一个**状态机**：按分区消费 **trading_(分区)**，顺序处理，保证同一分区内事件顺序一致。
  - **主从热备**：通过 **Zookeeper** 确定主节点；**仅主节点**对外产生输出（写 trading_result_(分区)、response、orderReq_(币对) 等）；**从节点**：
    - 消费**主节点写入的** **trading_result_(分区)**（该 topic 有多个 partition，主节点各 ringBuffer 分别写各自 partition），据此**确定主节点已处理的 trading_(分区) 的位点**；
    - 消费 **trading_(分区)** 时**不会比主节点快**（以主节点位点为界或限速），保证与主节点顺序一致；
    - 对自身状态机结果与主节点输出的 **trading_result_(分区)** 进行**结果比对**（可按 partition 维度），用于热备与故障切换。

```
                    写入 trading_(分区) 的来源
    ┌──────────────────────────────────────────────────────────────┐
    │   open_api           message_dispatch           行情服务       │
    │   (用户下单)           (成交信息按分区分发)        (标记价/指数价)   │
    │        \                      |                      /       │
    │         \                     |                     /        │
    │          \                    ▼                    /          │
    │           └──────────▶ trading_(分区) ◀──────────┘          │
    │                              │                               │
    └──────────────────────────────┼──────────────────────────────┘
                                   │ 唯一输入
                                   ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  trading-server（状态机，主从热备）                             │
    │  · 消费 trading_(分区)，按分区顺序处理                         │
    │  · Zookeeper 选主 → 仅主节点输出（trading_result_(分区)、response、orderReq_(币对) 等按需） │
    │  · 从节点：消费主节点的 trading_result_(分区)（多 partition，各 ringBuffer 对应各 partition）确定主节点位点，消费 trading_(分区) 不超前主节点，并做结果比对；不输出
    └──────────────────────────────────────────────────────────────────────────────┘
```

## 2.3 分区与实例

- 分区示例：**分区1**、**分区2**、**...**（每个分区对应一个 **trading_(分区)** topic）。
- 每个 trading-server 实例对应一个 **trading_(分区)**，消费该分区即该实例的**唯一输入**；实例内会在启动时根据配置或 CPU 核数设定 **N 个槽位线程**，每个线程对应一个独立的 **trading-book**，按槽位顺序处理。

## 2.4 trading-server / trading-book 逻辑

1. **分配（实例级）**：trading-server 通过 **etcd / Zookeeper** 分配并绑定 **trading_(分区)**，负责：下单、结算、开平仓。**主从是 trading-server 实例级别**（见 2.2），与单个 trading-book 无关。
2. **trading-book 隔离**：实例内部的 N 个 trading-book 彼此完全隔离：
   - 每个 trading-book 由「一个工作线程 + 一个队列 + 一个 `RingBufferTradingBook`」组成；
   - 每个 trading-book 仅存储**一组用户**的订单与持仓（通过 `userSlot(uid)` 映射）；
   - 不同 trading-book 之间**不共享内存状态**，也不需要通过 Zookeeper 协调 master/slave。
3. **主从维度（实例级）**：
   - Zookeeper 只负责在多个 trading-server 实例之间选主，确定当前分区的**主实例**；
   - 主实例内部：每个 **ringBuffer**（每个 trading-book）**分别输出到 trading_result_(分区) 中各自对应的 partition**（槽位 i → partition i）；此外按需写 response、order_req_(币对)。
   - 备用实例（从实例）：消费**同一** **trading_(分区)** 做状态机副本，并**消费主节点写入的 trading_result_(分区)**（多 partition，与主节点各 ringBuffer 对应）；据此确定主节点已处理的 trading_(分区) 位点；从节点消费 trading_(分区) 的**速度不会比主节点快**（以主节点位点为界）；从节点对本地状态机结果与主节点输出的 trading_result_(分区) 进行**结果比对**。从实例不对外输出。

## 2.5 单线程拉取与按指令类型分发（与代码一致）

trading-server 内由**单个线程**从 Kafka 拉取 **trading_(分区)** 数据；每条消息根据**指令类型**决定提交给**多队列**还是**单队列**处理：

| 指令类型 | 分发方式 | 说明 |
|----------|----------|------|
| **行情数据**（如 `UPDATE_MARK_PRICE`、`UPDATE_INDEX_PRICE`） | **所有队列** | 每个槽位队列都提交一份任务，保证所有 RingBufferTradingBook 都更新标记价/指数价。 |
| **币对配置指令** | **所有队列** | 与行情类似，需所有槽位感知配置变更（若后续增加该指令，同样 for 循环投递到所有 taskArray）。 |
| **用户维度的指令**（`NEW_ORDER`、`CANCEL_ORDER`、`MATCH`、`TRANSFER`、`CREATE_USER`） | **按 uid 划分到某一队列** | 从消息中取 `uid`，用 `userSlot(uid)` 映射到固定槽位，只向该槽位的队列提交任务；同一 uid 始终进同一队列，保证顺序。 |

```
                    Kafka trading_(分区)
                              │
                    ┌─────────▼─────────┐
                    │  单线程 poll 拉取   │  MessageQueueService "poll-message"
                    └─────────┬─────────┘
                              │
                    ┌─────────▼─────────┐
                    │ handleMessage     │  按 request.command 分支
                    └─────────┬─────────┘
              ┌───────────────┼───────────────┐
              │               │               │
     UPDATE_MARK_PRICE   NEW_ORDER / MATCH   ...
     UPDATE_INDEX_PRICE  (含 uid)
              │               │
              ▼               ▼
     for (i=0..N)         slot = userSlot(uid)
     taskArray[i].add()   taskArray[slot].add()
     （所有队列）           （单队列）
              │               │
              ▼               ▼
     ┌─────────────────────────────────────┐
     │  槽位 0    槽位 1    ...    槽位 N    │  每槽一个 LinkedBlockingQueue + 一个处理线程
     │  Book 0   Book 1    ...    Book N    │  对应 RingBufferTradingBook；各 Book 分别输出到 trading_result_(分区) 的 partition 0, 1, …, N
     └─────────────────────────────────────┘
```

- **当前代码**：`MessageHandler.handleMessage` 中 `UPDATE_MARK_PRICE` / `UPDATE_INDEX_PRICE` 走 for 循环投递所有队列；`CANCEL_ORDER`、`NEW_ORDER`、`MATCH`、`TRANSFER`、`CREATE_USER` 取 `uid` 后 `userSlot(uid)` 投递单队列。槽位数为 `PROCESSOR_THREAD_COUNT`，与 CPU 核数一致。
