# 交易系统架构说明

本文档描述分布式交易系统的组件、数据流与关键机制。系统核心路径基于 **Aeron**（Aeron Cluster + Aeron Archive + MDC）实现低延迟通信，使用 **SBE (Simple Binary Encoding)** 作为序列化协议。

---

## 1. 整体概览

运行环境与基础框架：

- **JDK 版本**：21（`maven.compiler.source` / `target` = 21）。
- **Spring Boot 版本**：3.5.11（父 POM 继承自 `spring-boot-starter-parent:3.5.11`）。

系统负责：用户下单、撮合、结算、风控校验与数据持久化。

### 1.1 核心基础设施

| 组件 | 用途 |
|------|------|
| **Aeron Cluster** | match-engine 共识与撮合（Raft 指令共识） |
| **Aeron MDC** | 多播分发（match-engine 撮合结果 + trading-server 三条输出流） |
| **Aeron Archive** | 本地录制（Spy subscription），支持 ReplayMerge 断线追赶 |
| **SBE** | 服务间二进制序列化协议（零拷贝、零 GC） |
| **MySQL** | 持久化存储（通过 flush-service 异步写入） |
| **Redis (Redisson)** | 缓存 |

### 1.2 核心服务

| 服务                    | 模块                  | 职责 |
|-----------------------|---------------------|------|
| **tk-match-engine**   | `tk-match-engine`      | Aeron Cluster 部署，撮合引擎，维护订单簿 |
| **tk-trading-server** | `tk-trading-server` | 结算状态机，账户/订单/持仓管理（结果共识） |
| **tk-open-api**       | `tk-open-api`          | 用户 REST API 网关 |
| **tk-trading-flush**  | `trading-flush`     | 消费结算结果，写入 MySQL |
| **行情 service**        | —                   | 标记价格、指数价格、K 线 |
| **tk-admin-api**      | `tk-admin-api`      | 管理后台 |

### 1.3 整体数据流

```
open-api (REST)
    │                              ▲
    │ 用户请求                      │ response 流 (MDC)
    ▼                              │
trading-server shard N (结果共识, 三层架构)
    │  内存层: 全量 account → RingBuffer 分区
    │  共识层: Raft log (全节点处理 → 更新内存 + 输出三条流)
    │  输出层: matchOrderReq / response / tradingResult
    │
    │  matchReplayMerge             ▲ MatchResult MDC
    │  (Leader 独立线程              │ (Leader ReplayMerge)
    │   本地 Archive →              │
    │   Aeron Cluster Client)       │
    ▼                              │
match-engine Cluster (指令共识, 单线程, 严格 1:1)
    │  所有节点执行相同命令, 维护相同 OrderBook
    │
    │  MDC Publication (SBE) + Spy → Archive
    ▼
┌──────────────────────────────────────┐
│  trading-server Leader (ReplayMerge)  │ → 结算
│  行情 service (ReplayMerge)           │ → K 线 / 标记价格 / 推送
└──────────────────────────────────────┘
```

---

## 2. match-engine：Aeron Cluster + 指令共识

### 2.1 架构概述

match-engine 部署为 **Aeron Cluster（3 节点）**，使用 **Raft 共识**对输入指令排序。所有节点执行相同的有序命令流，维护相同的 OrderBook 状态（**指令共识**）。

- **输入**：trading-server 各 shard 的 Leader 作为 Aeron Cluster client，通过 session 发送 `PushOrderCommand` / `CancelOrderCommand` / `UpdateMarketCommand`（SBE 编码）。
- **输出**：撮合结果 `MatchResult` 通过 **Aeron MDC** 多播给所有消费者（trading-server、行情 service）。
- **录制**：所有节点将结果写入本地 IPC publication，由 **Aeron Archive** 通过 **spy subscription** 零拷贝录制。
- **快照**：Aeron Cluster 内置 `onTakeSnapshot` / `onLoadSnapshot`，SBE 编码。

### 2.2 单线程撮合

所有 symbol 在同一个 Aeron Cluster 中处理，`ClusteredService.onSessionMessage()` 在 Cluster 共识线程上**单线程**执行。

- `engines: Map<Integer, MatchEngine>` — symbolId → MatchEngine（每个 MatchEngine 持有一个 OrderBook）。
- 单线程消除所有并发复杂度。
- 当前吞吐需求（~5 万 TPS）在 SBE + 单线程能力范围内。

```
  Aeron Cluster Sessions (ingress)
  ┌──────────────────────────────────┐
  │  session 0: trading-server shard0│
  │  session 1: trading-server shard1│
  │  ...                             │
  └──────────────┬───────────────────┘
                 │ PushOrderCommand / CancelOrderCommand (SBE)
                 ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  ClusteredService (单线程, 所有节点执行)                        │
  │                                                              │
  │  严格 1:1: 每条命令 → 恰好一条 MatchResult                     │
  │                                                              │
  │  onSessionMessage(session, buffer):                          │
  │      cmd = SbeDecoder.decode(buffer)                         │
  │      result = engines.get(cmd.symbolId).process(cmd)         │
  │      if (result == null):                                    │
  │          result = MatchResult.empty(cmd.symbolId)            │
  │      result.matchSeq = nextMatchSeq                          │
  │                                                              │
  │      if (nextMatchSeq > lastRecordedMatchSeq):               │
  │          localPub.offer(encode(result))  // 全节点 → spy      │
  │          if (role == LEADER):                                │
  │              mdcPub.offer(encode(result))// Leader → 网络    │
  │      nextMatchSeq++                                          │
  │                                                              │
  │  engines:                                                    │
  │    BTC(1) → MatchEngine → OrderBook (TreeMap+LinkedHashMap)  │
  │    ETH(2) → MatchEngine → OrderBook                         │
  │    ...                                                       │
  └──────────────────────────────────────────────────────────────┘
                 │
                 ▼
  MDC Publication (Dynamic) + Spy → Archive
```

### 2.3 严格 1:1 输入输出 + matchSeq

每条进入 Raft log 的命令（PUSH_ORDER / CANCEL_ORDER / UPDATE_MARKET）**必须恰好产出一条 MatchResult**：

- 即使 `MatchEngine.process()` 返回 null（命令无实质结果），ClusteredService 也包装为空 MatchResult（trades=[], finishOrders=[]）。
- 每条 MatchResult 携带全局递增的 `matchSeq`（int64，从 0 开始），单线程天然保证连续。
- 不暴露 Raft log position（`orderReqOffset` 已移除），matchSeq 是唯一的外部排序标识。
- **对账**：Raft log 已应用 M 条 entry → Archive 中恰好有 M 条 MatchResult（matchSeq 0 到 M-1）。

**UPDATE_MARKET 输出场景**：
- 无影响 → 空 MatchResult。
- force=false 且存在不合规挂单 → 拒绝应用，空 MatchResult。
- force=true 且触发撤单 → MatchResult 包含被强制撤销的 finishOrders。

### 2.4 MDC 出口与 Spy 录制

- **MDC (Multi-Destination-Cast)**：Leader 通过 Dynamic MDC 发布 MatchResult。消费者（trading-server、行情）自行连接 control endpoint，match-engine 不感知消费者拓扑。
  - Channel 示例：`aeron:udp?control=0.0.0.0:40000|control-mode=dynamic`
- **双 publication 模型**：
  - `localPub` (IPC)：所有节点 offer，供本地 Archive spy 录制。
  - `mdcPub` (UDP MDC)：仅 Leader offer，网络发送给订阅者。
- **Spy subscription**：`aeron-spy:aeron:ipc` — 从 driver send buffer 直接读取，零拷贝，与撮合线程零耦合。
- **扩展录制 (extend recording)**：使用 `AeronArchive.extendRecording()` 确保 `recordingId` 在重启后保持不变，消费者可用固定 recordingId 发起 ReplayMerge。
- **Archive 一致性**：所有节点处理相同命令 → 确定性相同结果 → 所有节点的 Archive 内容一致。切主时新 Leader 的 Archive 已有完整历史。
- **启动去重**：
  - 启动时若已有 Archive 录制，先本地 replay 找到 `lastRecordedMatchSeq`。
  - Cluster 回放 Raft log 期间，matchSeq ≤ lastRecordedMatchSeq 的 MatchResult 跳过写入 Archive，避免重复。
- **matchSeq → Archive position 索引**：内部维护 `TreeMap<Long, Long>`（matchSeq → archive position），启动时 replay 重建，运行时动态更新。消费者可通过 matchSeq 查询对应 archive position 发起 ReplayMerge。

### 2.5 消费者通过 ReplayMerge 订阅

trading-server 和行情 service 通过 Aeron Archive 的 **ReplayMerge** 消费 match-engine 输出，自动处理断线追赶和实时消费的无缝衔接。

1. 消费者记录上次处理到的 `lastProcessedMatchSeq` 和对应的 `archivePosition`。
2. 创建 ReplayMerge，连接 match-engine 节点的 Archive（replay 通道）和 MDC（live 通道），从 archivePosition 开始。
3. ReplayMerge 自动：
   - **Replay 阶段**：从 Archive 回放历史 MatchResult。
   - **Merge 阶段**：replay 追上 live 时自动无缝切换。
   - **Live 阶段**：直接消费 MDC 实时数据。
4. 消费者始终使用同一个 `replayMerge.poll(handler, fragmentLimit)` 接口，无需区分阶段。
5. 消费者用 matchSeq 做间隙检测：收到 matchSeq N 后下一条必须是 N+1，否则告警。
6. match-engine 切主时：消费者检测 live MDC 中断 → 重建 ReplayMerge，连接新 Leader 的 Archive → 用 matchSeq 去重继续消费，无数据丢失。

### 2.6 Cluster 快照

OrderBook 快照由 Aeron Cluster 内置机制管理：

- **onTakeSnapshot(snapshotPublication)**：先编码 `SnapshotHeader`（nextMatchSeq, symbolCount）写入全局 matchSeq 状态，然后遍历所有 MatchEngine，对每个 symbol 编码 `SnapshotSymbolHeader`（symbolId, orderCount, nextSeq）+ 逐个挂单编码为 `SnapshotBookOrder`，通过 `snapshotPublication.offer(buffer)` 写入。SBE 编码。
- **onLoadSnapshot(snapshotImage)**：先读取 SnapshotHeader 恢复 `nextMatchSeq`，再解码各 SnapshotSymbolHeader + SnapshotBookOrder，按 seq 升序 restoreOrder 重建每个 symbol 的 OrderBook。Cluster 自动从快照对应的 log position 继续回放后续命令。
- Cluster 自动管理快照生命周期（触发、存储、清理、恢复），无需应用层调度。

### 2.7 选主与高可用

- **选主**：完全依靠 Aeron Cluster 内置 Raft 共识，不依赖 ZooKeeper。
- **指令共识**：所有节点处理相同命令，维护相同状态。切主无需补发、无数据丢失。
- **Archive 连续性**：所有节点维护完整的本地 Archive，切主后消费者连接新 Leader 即可。

---

## 3. 撮合流程与机制

撮合引擎的核心逻辑——订单簿、价格优先时间优先、各单类型行为——与通信层无关，在 Aeron 迁移中**不变**。

### 3.1 撮合流程（单币对）

- **唯一输入**：一条条 **OrderCommand**（PUSH_ORDER / CANCEL_ORDER / UPDATE_MARKET），经 Aeron Cluster Raft log 全序排列后，在 `onSessionMessage()` 中按序处理。
- **处理步骤**：
  1. 从 Cluster log 取一条命令，SBE 解码为 OrderCommand。
  2. **PUSH_ORDER**：根据 priceType 走 **LIMIT** / **MARKET** / **LIMIT_MAKER**（见 3.3～3.5）；产生 trades 与 finishOrders。
  3. **CANCEL_ORDER**：从订单簿移除对应 orderId，产生一条 FinishOrder（终态 CANCEL）。
  4. **UPDATE_MARKET**：更新交易对配置，若 force=true 且存在不合规挂单则触发强制撤单。
  5. 无论命令类型，**必须产出一条 MatchResult**（严格 1:1），分配 matchSeq 后通过 MDC 发布给消费者，同时 spy 录制到本地 Archive。

```
  Aeron Cluster Raft Log (全序)
         │
         ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  解析 OrderCommand (SBE)                                      │
  │  PUSH_ORDER  → 订单簿撮合/挂单                                │
  │  CANCEL_ORDER → 订单簿撤单                                    │
  │  UPDATE_MARKET → 更新配置 (可能强制撤单)                       │
  └──────────────────────────────────────────────────────────────┘
         │ 严格 1:1: 每条命令 → 一条 MatchResult
         ▼
  MatchResult { matchSeq, symbolId, taker, trades, finishOrders }
         │
         ├──▶ MDC Publication → trading-server / 行情 (ReplayMerge)
         └──▶ Spy → 本地 Archive
```

### 3.2 订单簿与优先规则

- **订单簿结构**：每个币对维护一本内存订单簿，分为**买盘**与**卖盘**。
  - **买盘**：按价格**从高到低**排序（TreeMap），同价档内按**时间先后**排队（FIFO）。
  - **卖盘**：按价格**从低到高**排序（TreeMap），同价档内按**时间先后**排队（FIFO）。
  - **同价档实现**：同价档使用 LinkedHashMap（key=seq），插入顺序即时间优先；撮合时队首 peek，仅 maker 完全成交时按 seq 移除，撤单按 seq O(1) 移除。
- **时间优先的定义**：**时间优先 = Aeron Cluster Raft log 中的命令顺序**。即：
  - 所有 OrderCommand 经 Raft 共识后全序排列；
  - 引擎按 log position 顺序处理命令，先处理的先挂单或先成交；
  - 同价档内先挂单的先被吃（FIFO），顺序由 Raft log 唯一决定。

- **priceType 与行为对照**：

| priceType     | 入市时撮合 | 未成交部分   | 会交叉时           |
|---------------|------------|--------------|--------------------|
| **LIMIT**     | 是（先撮后挂） | 挂入订单簿   | 正常撮合           |
| **MARKET**    | 是（只撮不挂） | PART_CANCEL  | -                  |
| **LIMIT_MAKER** | 否         | 挂入订单簿   | 整单拒绝，POST_ONLY_REJECT |

### 3.3 限价单（LIMIT）逻辑

- **入市时先撮合、再挂簿**：
  - 买限价：从卖盘**最低价**开始，若卖价 ≤ 限价则成交，直到本单量用完或没有可成交价格；**未成交部分**挂入买盘该限价档的**队尾**。
  - 卖限价：从买盘**最高价**开始，若买价 ≥ 限价则成交，直到本单量用完或没有可成交价格；**未成交部分**挂入卖盘该限价档的**队尾**。
- **成交价**：与对手盘成交时，使用**对手盘挂单价格**（maker 价格）。
- **输出**：每笔成交生成一条 TradeOrder；若某订单完全成交或进入终态，生成一条 FinishOrder（COMPLETED / PART_CANCEL 等）。

### 3.4 限价只做 Maker（LIMIT_MAKER / post-only）逻辑

- **只挂簿、不主动吃单**：订单必须以 maker 身份挂在盘口，**不允许**在入市时与现有盘口成交（即不能成为 taker）。
- **入市检查**：
  - **买 LIMIT_MAKER**：若买价 ≥ 当前卖一价（会立即成交），则**整单拒绝**，不挂入订单簿、不产生任何成交；返回一条 FinishOrder，终态为 **POST_ONLY_REJECT**，leaveVolume = 原委托量。
  - **卖 LIMIT_MAKER**：若卖价 ≤ 当前买一价（会立即成交），则**整单拒绝**，同上返回 POST_ONLY_REJECT。
- **通过检查时**：仅挂入对应买卖盘限价档队尾；后续若被对手单吃掉，则以 maker 身份成交。
- **典型用途**：做市、避免 taker 手续费、保证挂单方始终为 maker。

### 3.5 市价单（MARKET）逻辑

- **只撮合、不挂簿**：从对手盘最优价开始依次吃单，直到本单量用完或对手盘空。
- **未成交部分**：不再挂入订单簿，以 **PART_CANCEL** 终态输出一条 FinishOrder；若全部成交则输出 COMPLETED。
- **价格**：每笔成交使用当时对手盘档位价格，无本单限价约束。

### 3.6 状态机视角

- 订单在撮合侧的状态可抽象为：**挂单中**（在订单簿）→ **部分成交** / **完全成交** / **已撤单**。
- **PUSH_ORDER** 驱动：新单入市、可能立即产生多笔成交与多条 FinishOrder（maker/taker 终态）。
- **CANCEL_ORDER** 驱动：从订单簿移除该单，产生一条 FinishOrder（CANCEL，剩余量 leaveVolume/leaveAmount）。
- 撮合引擎**不直接写库、不操作账户/持仓**，只维护订单簿并产出 MatchResult；结算与持久化由 trading-server 完成。

---

## 4. trading-server：结果共识

### 4.1 概述

trading-server 采用**结果共识**，架构分为三层：**内存层**、**共识层**、**输出层**。

- **为什么不用指令共识**：trading-server 处理 NEW_ORDER 时有外部副作用（发 order_req 给 match-engine、发 response 给 open-api、写 trading_result 给 flush-service）。如果所有副本都执行命令，会产生重复副作用。
- **结果共识**：只有 Leader 接收外部请求并处理。内存层与共识层数据隔离，Leader 处理时不查询共识层。Follower 通过共识层同步数据到内存层。

```
  trading-server shard N  (三层架构, 内存层与共识层数据隔离)
  ╔══════════════════════════════════════════════════════════════╗
  ║                                                              ║
  ║  内存层 (全量 account 数据, 按用户分 RingBuffer)               ║
  ║  ┌──────────────────────────────────────────────────────────┐ ║
  ║  │  RingBuffer[0..N]: 账户/订单/持仓                         │ ║
  ║  │  Leader: 直接读写 (处理时不查询共识层)                     │ ║
  ║  │  Follower: 由共识层通知更新                                │ ║
  ║  └──────────────────────────────────────────────────────────┘ ║
  ║           ↑ 隔离 ↓                                           ║
  ║  共识层 (独立数据副本 + Raft log)                              ║
  ║  ┌──────────────────────────────────────────────────────────┐ ║
  ║  │  Leader: 变更 → Raft log → 共识层更新                     │ ║
  ║  │  Follower: Raft log → 共识层更新 → 通知内存层更新          │ ║
  ║  └──────────────────────────────────────────────────────────┘ ║
  ║                                                              ║
  ║  输出层 (三条 MDC 流, 全节点本地录制, Leader 网络发送)          ║
  ║  ┌──────────────────────────────────────────────────────────┐ ║
  ║  │  matchOrderReq  → match-engine   (撮合指令)              │ ║
  ║  │  response        → open-api       (用户响应)              │ ║
  ║  │  tradingResult   → flush-service  (持久化数据)             │ ║
  ║  └──────────────────────────────────────────────────────────┘ ║
  ║                                                              ║
  ║  matchReplayMerge 线程 (仅 Leader)                            ║
  ║  ┌──────────────────────────────────────────────────────────┐ ║
  ║  │  本地 matchOrderReq Archive → Aeron Cluster Client       │ ║
  ║  │  → match-engine (独立线程, 不反压共识状态机)               │ ║
  ║  └──────────────────────────────────────────────────────────┘ ║
  ║                                                              ║
  ╚══════════════════════════════════════════════════════════════╝
```

### 4.2 内存层：RingBuffer 分区

全量 account 数据驻留内存，按用户划分到不同的 **RingBuffer**：

- 每个 RingBuffer 负责一组用户（`ringBuffer = userSlot(uid)`），持有该组用户的账户余额、订单、持仓、冻结等全量状态。
- **内存层与共识层数据隔离**：Leader 处理下单/结算时，只读写内存层数据，**不查询共识层**。共识层有自己独立的数据副本。

**Leader 与 Follower 的分工**：

| | Leader | Follower |
|--|--------|----------|
| **接收外部请求** | 接收 open-api 请求、消费 MatchResult (ReplayMerge) | 不接收 |
| **处理请求** | 在内存层 RingBuffer 中执行业务逻辑，**不查询共识层** | 不处理 |
| **内存层更新** | 处理请求时直接修改内存层 | 共识层更新后通知 → 更新内存层 |
| **共识层** | 将变更通过 Raft log 推送给共识层 | 接收 Raft log → 更新共识层数据 → 通知更新内存层 |
| **输出三条流** | Raft log 提交后输出（Archive + MDC 网络） | Raft log 提交后输出（Archive） |
| **matchReplayMerge** | 运行（推送给 match-engine） | 不运行 |

### 4.3 共识层与内存层的数据隔离

共识层和内存层维护**各自独立的数据副本**，读写路径不交叉。

**共识层记录的元数据**：
- **RingBuffer 数量**：在共识层中记录，通常不可变更。
- **每个 RingBuffer 的 matchSeq 位点**：每个 RingBuffer 分别记录已处理的 MatchResult 的 matchSeq，随业务变更一起提交到共识层。

```
  共识层数据结构
  ═══════════════════════════════════════════════════════════════

  共识层 {
    ringBufferCount: int                    // RingBuffer 数量 (不可变)
    ringBufferMatchSeq[0]: int64            // RingBuffer 0 已处理的 matchSeq
    ringBufferMatchSeq[1]: int64            // RingBuffer 1 已处理的 matchSeq
    ...
    ringBufferMatchSeq[N]: int64            // RingBuffer N 已处理的 matchSeq
    accountData: ...                        // 全量 account 数据副本
  }
```

```
  Leader 数据流
  ═══════════════════════════════════════════════════════════════

  open-api 请求 ──┐
                   ├─ RingBuffer[i] 处理 (只读写内存层, 不查询共识层)
  MatchResult ────┘                │
  (ReplayMerge)                    ▼
                            变更 (delta) + ringBuffer[i].matchSeq
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
              内存层已更新    Raft log 提交    输出三条流
              (处理时直接     → 共识层更新    (Archive +
               修改完毕)       (含 matchSeq)  MDC 网络)


  Follower 数据流
  ═══════════════════════════════════════════════════════════════

  Raft log (已提交)
      │
      ▼
  共识层数据更新 (含 ringBuffer matchSeq 位点)
      │
      │ 通知
      ▼
  内存层更新 (对应 RingBuffer)
      │
      ▼
  输出三条流 (Archive)
```

**关键约束**：

- **Leader 处理不依赖共识层**：Leader 在 RingBuffer 中处理下单、结算等业务逻辑时，所有数据查询和修改都在内存层完成。处理完毕后，将变更作为 Raft log 条目提交，共识层异步更新。这保证了处理性能不受共识延迟影响。
- **Follower 内存层由共识层驱动**：Follower 不独立处理任何请求。Raft log → 共识层数据更新 → 通知内存层更新，保证 Follower 内存层最终与 Leader 一致。
- **数据一致性**：Leader 的内存层通过直接处理保持最新；Follower 的内存层通过共识层同步保持最新。两条路径的最终状态一致。
- **RingBuffer 数量不可变**：共识层记录 RingBuffer 数量，运行期间不允许变更，保证 Leader 和 Follower 的分区映射一致。
- **matchSeq 位点追踪**：每个 RingBuffer 独立记录已处理的 matchSeq，随业务变更一起提交到共识层，用于切主后恢复消费位点。

### 4.4 切主流程

```
  切主流程
  ═══════════════════════════════════════════════════════════════

  旧 Leader 失败
      │
      ▼
  新 Leader 当选
      │
      ▼
  新 Leader 向共识层推送切主消息 (LeaderChange)
      │
      ▼
  切主消息通过 Raft log 提交
      │
      ▼
  收到切主消息 → 确认所有待处理的共识数据已处理完毕
      │
      ▼
  从共识层获取所有 RingBuffer 的 matchSeq
      │
      ├─ ringBufferMatchSeq[0] = 150
      ├─ ringBufferMatchSeq[1] = 148   ← min
      ├─ ringBufferMatchSeq[2] = 152
      └─ ...
      │
      ▼
  取 min(ringBufferMatchSeq) = 148
      │
      ▼
  从 matchSeq=148 开始消费 MatchResult (ReplayMerge)
      │
      │ · matchSeq ≤ 各 RingBuffer 已处理位点 → 该 RingBuffer 跳过
      │ · matchSeq > RingBuffer 已处理位点 → 正常处理
      │ · 确保不丢失任何 RingBuffer 尚未处理的 MatchResult
      ▼
  正常服务
```

**切主消息的作用**：
- 新 Leader 当选后，首先向共识层推送一条 **切主消息**（LeaderChange）。
- 切主消息通过 Raft log 提交后被处理，此时可以确认：Raft log 中排在切主消息之前的所有条目都已被共识层处理完毕。
- 这保证了从共识层读取的 `ringBufferMatchSeq` 是完整、一致的。

**从最小 matchSeq 开始消费**：
- 不同 RingBuffer 处理不同用户的 MatchResult，各自的 matchSeq 进度可能不同。
- 取所有 RingBuffer 的 `min(matchSeq)` 作为消费起点，确保不丢失任何 RingBuffer 尚未处理的数据。
- 已处理过的 MatchResult 在对应 RingBuffer 中通过 matchSeq 比较跳过，不会重复结算。

```
  Leader 独有:
  ═══════════════════════════════════════════════════════════════

  matchReplayMerge 线程:
    本地 matchOrderReq Archive → Aeron Cluster Client → match-engine
```

### 4.5 输出层：三条 MDC 流

输出层分为三条独立的流，**全节点**在处理 Raft log 时输出。

| 流 | 内容 | 消费者 | 用途 |
|-----|------|--------|------|
| **matchOrderReq** | PushOrderCommand / CancelOrderCommand | match-engine | 撮合指令（通过 matchReplayMerge 转发） |
| **response** | 订单响应（接受/拒绝/状态更新） | open-api | 响应用户请求 |
| **tradingResult** | 结算结果（账户变更、订单更新、成交记录） | flush-service | 持久化到 MySQL |

每条流的架构（与 match-engine 相同模式）：
- `localPub` (IPC)：全节点 offer，Spy → 本地 Archive 录制。
- `mdcPub` (UDP MDC, Dynamic)：仅 Leader offer，网络发送给消费者。
- 扩展录制（extend recording）：确保 recordingId 跨重启稳定。
- 全节点 Archive 内容一致（因为都处理相同的 Raft log → 确定性相同的输出），切主后消费者可连接新 Leader 的 Archive。

### 4.6 matchReplayMerge：撮合指令转发线程

Leader 启动独立的 **matchReplayMerge 线程**，负责将 matchOrderReq 流中的撮合指令推送给 match-engine。

```
  matchReplayMerge 线程 (仅 Leader 运行)
  ═══════════════════════════════════════════════════════════════

  本地 matchOrderReq Archive
      │
      │ ReplayMerge (本地回放)
      ▼
  matchReplayMerge 线程
      │
      │ Aeron Cluster Client Session
      ▼
  match-engine Cluster
```

**设计要点**：

- **独立线程，不反压共识状态机**：matchReplayMerge 线程与 Raft log 处理完全解耦。即使 match-engine 暂时不可用或处理缓慢，共识状态机仍正常运行，matchOrderReq 持续写入本地 Archive 积累。
- **本地回放**：从本地 matchOrderReq Archive 读取，不走网络。Archive 是 Raft log 处理的确定性输出，数据完整性由共识保证。
- **保证不丢数据**：matchOrderReq 先持久化到 Archive，再由 matchReplayMerge 异步转发。即使 Leader 崩溃，新 Leader 的 Archive 中也有完整的 matchOrderReq 记录（因为 Follower 也写了相同的 Archive），新 Leader 的 matchReplayMerge 从上次发送位置继续。
- **去重**：match-engine 可通过 matchOrderReq 中的序号去重，防止 Leader 切换导致的重复发送。
- **切主流程**：
  1. 新 Leader 启动 matchReplayMerge 线程。
  2. 确定上次已发送到 match-engine 的位置（通过序号或 match-engine 反馈）。
  3. 从该位置继续回放 matchOrderReq Archive。

### 4.7 Leader 处理流程

```
  trading-server Leader 处理流程
  ═══════════════════════════════════════════════════════════════

  输入 (仅 Leader 接收):
    · open-api: 用户下单/撤单 (REST)
    · match-engine MDC: 撮合结果 (ReplayMerge, 仅 Leader 消费)
    · 行情: 标记价格 / 指数价格

  处理 NEW_ORDER:
    1. 按 uid hash 路由到 RingBuffer[i]
    2. RingBuffer[i] 处理: 校验 + 冻结保证金 (只读写内存层, 不查共识层)
    3. 内存层已更新 (处理过程中直接修改)
    4. 变更写入 Raft log → 共识层更新
    5. 输出 PushOrderCommand → matchOrderReq 流 (Archive + MDC)
    6. 输出订单响应 → response 流 (Archive + MDC)
    → matchReplayMerge 线程异步:
    7. 从 matchOrderReq Archive 读取 → Aeron Cluster Client → match-engine

  处理 MatchResult (来自 match-engine):
    1. 过滤本 shard uid (按 buyShardId / sellShardId / shardId)
    2. 按 uid hash 路由到 RingBuffer[i]
    3. RingBuffer[i] 结算: 更新账户/订单/持仓 (只读写内存层, 不查共识层)
    4. 内存层已更新 (处理过程中直接修改)
    5. 变更 + ringBuffer[i].matchSeq 位点 → 写入 Raft log → 共识层更新
    6. 输出结算结果 → tradingResult 流 (Archive + MDC)
```

### 4.8 分区模型

- **shard 分区**：每个 trading-server 实例负责一个 shard（一组用户）。
- **RingBuffer 分区**：shard 内按 `uid hash` 将用户划分到不同的 RingBuffer，每个 RingBuffer 独立处理自己负责的用户。
- RingBuffer 提供 shard 内的并行处理能力，同一用户的所有操作在同一个 RingBuffer 中串行执行，保证单用户操作的顺序性。

### 4.9 match 结果消费

**仅 Leader** 通过 ReplayMerge 订阅 match-engine 的 MDC 输出，在内部完成 MatchResult → per-user 拆分和路由：

- Leader 订阅全量 MatchResult 流。
- 对每条 MatchResult，遍历 trades 和 finishOrders，按 `buyShardId` / `sellShardId` / `shardId` 过滤属于本 shard 的数据。
- 按 uid hash 路由到对应的 RingBuffer 处理。
- 每个 RingBuffer 处理完毕后，将该 RingBuffer 的 matchSeq 位点随变更一起提交到共识层。
- Follower 不消费 MatchResult，其内存层数据通过 Raft log 同步。
- 切主后新 Leader 从 `min(ringBufferMatchSeq)` 开始消费，各 RingBuffer 通过 matchSeq 比较跳过已处理的数据（详见 §4.4）。

---

## 5. 交易协议（SBE）

### 5.1 SBE 消息定义

服务间协议使用 **SBE (Simple Binary Encoding)**，替代原有 JSON (fastjson2)。

**设计原则**：
- BigDecimal → `Decimal64 { mantissa: int64, exponent: int8 }` composite 类型。
- String symbol → `symbolId: uint32`，映射通过 `UpdateMarketCommand` 同步。
- 枚举类型使用 `uint8` 编码。
- 按命令类型拆分为独立消息（非 union），通过 `messageHeader.templateId` 区分。

**trading-server → match-engine (Aeron Cluster Session)**：

| 消息 | ID | 字段 |
|------|-----|------|
| **PushOrderCommand** | 1 | symbolId(u32), orderId(i64), uid(i64), shardId(i32), marketId(i64), side(Side), priceType(PriceType), timeInForce(TimeInForce), price(Decimal64), volume(Decimal64), amount(Decimal64), createTime(i64) |
| **CancelOrderCommand** | 2 | symbolId(u32), orderId(i64), uid(i64), shardId(i32) |
| **UpdateMarketCommand** | 3 | symbolId(u32), priceScale(i32), qtyScale(i32), minQty(Decimal64), minTradeQuoteAmount(Decimal64), configVersion(i64), force(BooleanType) |

**match-engine → 消费者 (MDC + Archive)**：

| 消息 | ID | 字段 |
|------|-----|------|
| **MatchResult** | 10 | matchSeq(i64), symbolId(u32), takerUid(i64), takerOrderId(i64), takerShardId(i32); **group trades**: index(i64), price(Decimal64), volume(Decimal64), buyUid(i64), sellUid(i64), buyOrderId(i64), sellOrderId(i64), buyShardId(i32), sellShardId(i32), takerOrderId(i64), takerUid(i64); **group finishOrders**: uid(i64), orderId(i64), status(FinishStatus), rejectReason(RejectReason), leaveAmount(Decimal64), leaveVolume(Decimal64), shardId(i32) |

**快照消息**（Cluster onTakeSnapshot / onLoadSnapshot）：

| 消息 | ID | 字段 |
|------|-----|------|
| **SnapshotHeader** | 20 | nextMatchSeq(i64), symbolCount(i32) |
| **SnapshotSymbolHeader** | 22 | symbolId(u32), orderCount(i32), nextSeq(i64) |
| **SnapshotBookOrder** | 21 | symbolId(u32), orderId(i64), uid(i64), shardId(i32), side(Side), price(Decimal64), volume(Decimal64), remainingVolume(Decimal64), amount(Decimal64), remainingAmount(Decimal64), seq(i64) |

### 5.2 枚举定义

| 枚举 | 编码 | 值 |
|------|------|-----|
| `Side` | uint8 | BUY(0), SELL(1) |
| `PriceType` | uint8 | LIMIT(0), MARKET(1), LIMIT_MAKER(2) |
| `TimeInForce` | uint8 | GTC(0), IOC(1), FOK(2) |
| `FinishStatus` | uint8 | COMPLETED(0), CANCEL(1), PART_CANCEL(2), EXCEPTION(3), REJECT(4), POST_ONLY_REJECT(5) |
| `RejectReason` | uint8 | NONE(0), INVALID_ORDER_ID(1), DUPLICATE_ORDER_ID(2), ORDER_EXPIRED(3), INVALID_PRICE_TYPE(4), INVALID_PRICE(5), PRICE_TICK_INVALID(6), INVALID_QUANTITY(7), INVALID_NOTIONAL(8), INVALID_TIME_IN_FORCE(9), POST_ONLY_WOULD_CROSS(10), FOK_NOT_FILLABLE(11), UNKNOWN(255) |

### 5.3 MatchResult 字段约定

- `matchSeq`：全局递增序号（int64，从 0 开始），唯一标识每条 MatchResult。单线程保证严格连续，无间隙。
- `symbolId`：触发本次撮合的交易对。
- `takerUid`、`takerOrderId`、`takerShardId`：taker 方信息（UPDATE_MARKET 等非订单命令时 takerUid = -1 sentinel）。

### 5.4 TradeOrder 字段约定

每条 TradeOrder 完整描述一次撮合事件的双方身份和角色：

- `index`：同一 match 内序号（0-based，每笔 fill 递增）。`(matchSeq, index)` 唯一标识一笔成交。
- `price`、`volume`：成交价格与数量。
- `takerUid`、`takerOrderId`：taker 方。
- `buyUid`、`sellUid`、`buyOrderId`、`sellOrderId`：买卖双方。
- `buyShardId`、`sellShardId`：买卖方所属 shard（用于 trading-server 过滤）。

### 5.5 FinishOrder 字段约定

- `uid`、`orderId`：所属用户和订单。
- `status`：终态（COMPLETED / CANCEL / PART_CANCEL / EXCEPTION / REJECT / POST_ONLY_REJECT）。
- `rejectReason`：拒绝原因（仅 REJECT / POST_ONLY_REJECT 时有值）。
- `leaveAmount`、`leaveVolume`：未成交的 amount 或 volume。
- `shardId`：所属 shard。

---

## 6. 持久化数据结构

trading-server 结算后将变更输出到 flush-service 落库。需持久化的核心数据结构与表对应如下。

| 逻辑实体 | 表名 | 主要字段（概要） | 用途 |
|----------|------|------------------|------|
| **账户** | `account` | uid, coin_id, coin_name, available_balance, cross_margin_frozen, isolated_margin_frozen, order_frozen, txid, ctime, mtime | 用户各币种可用、冻结保证金、挂单冻结；txid 做版本/乐观锁 |
| **订单** | `co_order` | id, uid, position_id, symbol, market_id, amount, volume, price_type, price, status, open, side, position_type, margin, deal_volume, deal_amount, avg_deal_price, fee, leverage_level, realized_amount, cancel_order, txid, ctime, mtime, completed_time, cancel_time | 委托单生命周期与成交信息；status：0 初始化 1 部分成交 2 完全成交 3 部分成交撤销 4 撤销 5 异常 |
| **持仓** | `co_position` | id, uid, symbol, market_id, volume, close_volume, pending_close_volume, fee, open_price, close_price, hold_amount, realized_amount, status, leverage_level, side, position_type, liq_order_id, txid, ctime, mtime | 用户某合约多/空持仓、保证金、已实现盈亏；status：1 未完成 0 已完成 |
| **成交** | `trade_order` | id, match_id, price, volume, status, full_match, index, taker_uid, maker_uid, buy_uid, sell_uid, buy_order_id, sell_order_id; ctime, mtime | 单笔成交记录 |
| **划转** | `transfer` | id, uid, transfer_id, coin_id, amount, txid, status, type, ctime, mtime | 入金/出金流水 |

---

## 7. 风控服务

### 7.1 数据持久化

- flush-service 消费 trading-server 的结算结果，写入数据库。
- 持久化服务**按版本写入最新数据**，保证版本一致。

### 7.2 ADL 服务（Auto-Deleveraging）

- 做**风险校验**。
- 爆仓后盘口无法完全承接的仓位，进入**对盘（ADL）** 处理。

### 7.3 风控检查（risk check）

- **触发**：用户持仓变更、标记价格变更时触发检查。
- **逻辑**：
  1. 风险率是否穿越强平线，判断用户是否可被强平。
  2. 若可强平，执行**爆仓残值分配**。

---

## 8. 行情服务（quote）

行情 service 通过 **ReplayMerge** 订阅 match-engine 的 MDC 输出，消费全量 MatchResult。

- 计算**标记价格**。
- 计算**指数价格**。
- 生成：
  - 交易 K 线
  - 指数 K 线
  - 标记价格 K 线

---

## 9. 完整架构图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            AERON EXCHANGE                                │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘

  open-api (REST/JSON)
      │                              ▲
      │ 用户请求                      │ response 流 (MDC, ReplayMerge)
      ▼                              │
  ╔════════════════════════════════════════════════════════════════════╗
  ║  trading-server shard N  (结果共识, 三层架构)                        ║
  ║                                                                    ║
  ║  内存层: 全量 account → RingBuffer[0..N] (按 uid hash 分区)         ║
  ║  共识层: Raft log (全节点处理 → 更新内存层 + 输出三条流)             ║
  ║  输出层: 全节点本地 Archive, Leader 额外 MDC 网络发送                ║
  ║    · matchOrderReq  → match-engine  (撮合指令)                      ║
  ║    · response        → open-api      (用户响应)                      ║
  ║    · tradingResult   → flush-service  (持久化)                       ║
  ║                                                                    ║
  ║  matchReplayMerge (Leader 独立线程, 不反压共识)                      ║
  ║    本地 matchOrderReq Archive → Aeron Cluster Client → 撮合        ║
  ║                                                                    ║
  ╚═══════════┬════════════════════════════════════════════════════════╝
              │                                 ▲
              │ matchReplayMerge               │ MatchResult MDC
              │ (Aeron Cluster Client)         │ (Leader ReplayMerge)
              ▼                                 │
  ┌────────────────────────────────────────────┴───────────────────────┐
  │  match-engine Cluster  (3 nodes, Raft 指令共识)                      │
  │                                                                    │
  │  Raft Log → onSessionMessage (单线程, 严格 1:1)                     │
  │    → engines.get(symbolId).process(cmd)                            │
  │    → MatchResult (matchSeq 全局递增)                                │
  │                                                                    │
  │  Egress:                                                           │
  │    localPub (IPC, 全节点) → Spy → Archive (本地录制)                 │
  │    mdcPub (UDP MDC, Leader only) → 网络多播                         │
  └────────────────────────────────────────────────────────────────────┘
              │
              │ MatchResult MDC (Dynamic control-mode, SBE)
              │
      ┌───────┼────────────────────────┐
      ▼       ▼                        ▼
  trading   trading                ┌─────────┐
  server    server                │  行情     │
  shard 0   shard 1  ...         │ service  │
  (Leader                        │(ReplayMerge)
   ReplayMerge)                  └─────────┘
      │
      │ tradingResult 流
      │ (MDC, ReplayMerge)
      ▼
  ┌────────────────────┐
  │  flush-service     │
  │  → MySQL           │
  └────────────────────┘
```

---

## 10. 与当前代码的对应关系

### 10.1 核心模块

| 模块 | 说明 |
|------|------|
| `trading-protocol` | SBE schema + DTO 参考 |
| `match-engine` | Aeron Cluster 单线程 + MDC + Archive |
| `tk-trading-server` | 结果共识三层架构（内存层 RingBuffer 分区 + 共识层 + 输出层三条 MDC 流） |
| `open-api` | REST → trading-server，消费 response 流 (ReplayMerge) |
| `trading-flush` | 消费 trading-server tradingResult 流 (ReplayMerge) |

### 10.2 核心组件映射

| 组件 | 说明 |
|------|------|
| Aeron Cluster + MDC + Archive | match-engine 指令共识 + trading-server 三条输出流 |
| Aeron Cluster Raft | match-engine 选主 + trading-server 共识层 |
| Aeron Archive | 持久化录制与 ReplayMerge 消费 |
| Cluster 单线程 | match-engine 撮合线程模型 |
| ClusteredService | match-engine 撮合服务 |
| Cluster 快照 | 状态快照与恢复 |

### 10.3 保留不变的核心

- `MatchEngine`：process(OrderCommand) → MatchResult
- `OrderBook`：TreeMap + LinkedHashMap 订单簿
- `BookOrder`、`PriceLevel`：订单和价格档位
- Matcher 逻辑：LIMIT / MARKET / IOC / FOK / LIMIT_MAKER
- 持久化表结构：account / co_order / co_position / trade_order / transfer

---

## 11. 相关文档

- [市价单与交易对资产语义](doc/市价单与交易对资产语义.md)（BTC_USDT 示例：base/quote、市价 IOC 与业务锁仓、`OrderPayload` 字段；`MarketConfig.minTradableQuoteNotional`：剩余 quote 名义 **<** 阈值时业务完单）
- [Aeron 架构设计](openspec/changes/aeron-exchange/design.md)（match-engine 指令共识 + trading-server 结果共识三层架构，SBE 协议定义）
