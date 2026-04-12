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
| **Aeron MDC** | match-engine 撮合结果多播分发（Dynamic control-mode） |
| **Aeron Archive** | 撮合结果本地录制（Spy subscription），支持 ReplayMerge |
| **SBE** | 服务间二进制序列化协议（零拷贝、零 GC） |
| **MySQL** | 持久化存储 |
| **Redis (Redisson)** | 缓存 |

### 1.2 核心服务

| 服务 | 模块 | 职责 |
|------|------|------|
| **match-engine** | `match-engine` | Aeron Cluster 部署，撮合引擎，维护订单簿 |
| **trading-server** | `tk-trading-server` | 结算状态机，账户/订单/持仓管理（结果共识） |
| **open-api** | `open-api` | 用户 REST API 网关 |
| **flush-service** | `trading-flush` | 消费结算结果，写入 MySQL |
| **行情 service** | — | 标记价格、指数价格、K 线 |
| **admin-api** | `admin-api` | 管理后台 |

### 1.3 整体数据流

```
open-api (REST)
    │
    ▼
trading-server shard N (结果共识)
    │  Leader: 校验/冻结 → 发 OrderCommand
    │
    │  Aeron Cluster Session (SBE)
    ▼
match-engine Cluster (指令共识, 单线程撮合)
    │  所有节点执行相同命令, 维护相同 OrderBook
    │
    │  MDC Publication (SBE) + Spy → Archive
    ▼
┌──────────────────────────────────────┐
│  trading-server (ReplayMerge 消费)    │ → 结算 → flush-service → MySQL
│  行情 service (ReplayMerge 消费)      │ → K 线 / 标记价格 / 推送
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
  │  onSessionMessage(session, buffer):                          │
  │      cmd = SbeDecoder.decode(buffer)                         │
  │      engine = engines.get(cmd.symbolId)                      │
  │      result = engine.process(cmd)                            │
  │      if (result != null):                                    │
  │          localPub.offer(encode(result))   // 所有节点 → spy   │
  │          if (role == LEADER):                                │
  │              mdcPub.offer(encode(result)) // Leader → 网络   │
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

### 2.3 MDC 出口与 Spy 录制

- **MDC (Multi-Destination-Cast)**：Leader 通过 Dynamic MDC 发布 MatchResult。消费者（trading-server、行情）自行连接 control endpoint，match-engine 不感知消费者拓扑。
  - Channel 示例：`aeron:udp?control=0.0.0.0:40000|control-mode=dynamic`
- **双 publication 模型**：
  - `localPub` (IPC)：所有节点 offer，供本地 Archive spy 录制。
  - `mdcPub` (UDP MDC)：仅 Leader offer，网络发送给订阅者。
- **Spy subscription**：`aeron-spy:aeron:ipc` — 从 driver send buffer 直接读取，零拷贝，与撮合线程零耦合。
- **Archive 一致性**：所有节点处理相同命令 → 确定性相同结果 → 所有节点的 Archive 内容一致。切主时新 Leader 的 Archive 已有完整历史。

### 2.4 消费者通过 ReplayMerge 订阅

trading-server 和行情 service 通过 Aeron Archive 的 **ReplayMerge** 消费 match-engine 输出，自动处理断线追赶和实时消费的无缝衔接。

1. 消费者启动时，记录上次处理到的 Archive position（`lastProcessedPosition`）。
2. 创建 ReplayMerge，连接 match-engine 节点的 Archive（replay 通道）和 MDC（live 通道）。
3. ReplayMerge 自动：
   - **Replay 阶段**：从 Archive 回放历史 MatchResult。
   - **Merge 阶段**：replay 追上 live 时自动无缝切换。
   - **Live 阶段**：直接消费 MDC 实时数据。
4. 消费者始终使用同一个 `replayMerge.poll(handler, fragmentLimit)` 接口，无需区分阶段。
5. match-engine 切主时：消费者检测 live MDC 中断 → 重建 ReplayMerge，连接新 Leader 的 Archive → 从 lastProcessedPosition 继续，无数据丢失。

### 2.5 Cluster 快照

OrderBook 快照由 Aeron Cluster 内置机制管理：

- **onTakeSnapshot(snapshotPublication)**：遍历所有 MatchEngine，对每个 symbol 编码 `SnapshotHeader`（symbolId, reqOffset, orderCount, nextSeq）+ 逐个挂单编码为 `SnapshotBookOrder`，通过 `snapshotPublication.offer(buffer)` 写入。SBE 编码。
- **onLoadSnapshot(snapshotImage)**：解码 SnapshotHeader + SnapshotBookOrder，按 seq 升序 restoreOrder 重建每个 symbol 的 OrderBook。Cluster 自动从快照对应的 log position 继续回放后续命令。
- Cluster 自动管理快照生命周期（触发、存储、清理、恢复），无需应用层调度。

### 2.6 选主与高可用

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
  2. **PUSH_ORDER**：根据 priceType 走 **LIMIT** / **MARKET** / **LIMIT_MAKER**（见 3.3～3.5）；产生 trades 与 finishOrders，若有则组装 MatchResult。
  3. **CANCEL_ORDER**：从订单簿移除对应 orderId，产生一条 FinishOrder（终态 CANCEL）。
  4. 将 MatchResult 通过 MDC 发布给消费者，同时 spy 录制到本地 Archive。

```
  Aeron Cluster Raft Log (全序)
         │
         ▼
  ┌──────────────────────────────────────────────────────────────┐
  │  解析 OrderCommand (SBE)                                      │
  │  PUSH_ORDER → 订单簿撮合/挂单   CANCEL_ORDER → 订单簿撤单      │
  └──────────────────────────────────────────────────────────────┘
         │
         ▼
  MatchResult { taker, trades, finishOrders, orderReqOffset }
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

## 4. trading-server：结果共识（设计中）

### 4.1 概述

trading-server 采用**结果共识**：仅 Leader 执行命令并产生副作用，将结果复制给 Follower。

- **为什么不用指令共识**：trading-server 处理 NEW_ORDER 时有外部副作用（发 order_req 给 match-engine、发 response 给 open-api、写 trading_result 给 flush-service）。如果所有副本都执行命令，会产生重复副作用。
- **结果共识**：只有 Leader 产生副作用，Follower 仅应用结果，干净分离。

### 4.2 Leader 处理流程

```
  trading-server Leader
  ┌────────────────────────────────────────────────────────────┐
  │                                                            │
  │  输入:                                                      │
  │    · open-api: 用户下单/撤单                                │
  │    · match-engine MDC: 撮合结果 (ReplayMerge 消费)          │
  │    · 行情: 标记价格 / 指数价格                               │
  │                                                            │
  │  处理:                                                      │
  │    · NEW_ORDER → 校验/冻结 → Aeron session → match-engine   │
  │    · MatchResult → 过滤本 shard uid → 按 uid slot 结算      │
  │    · 结算结果 → 复制给 Follower + 发给 flush-service        │
  │                                                            │
  │  输出:                                                      │
  │    · response → open-api (按需)                             │
  │    · PushOrderCommand → match-engine Cluster session        │
  │    · 结算结果 → Follower (结果复制)                          │
  │    · 结算结果 → flush-service (落库)                         │
  │                                                            │
  └────────────────────────────────────────────────────────────┘
```

### 4.3 分区与槽位

- 按 shard 分区：每个 trading-server 实例负责一个 shard（一组用户）。
- 实例内按 uid hash 分槽：`slot = userSlot(uid)`，每个槽位独立的结算线程和 TradingBook。

### 4.4 match 结果消费

trading-server 直接通过 ReplayMerge 订阅 match-engine 的 MDC 输出，在内部完成 MatchResult → per-user 拆分和路由（吸收原 message-dispatch 的拆分逻辑）：

- 每个 shard 的 trading-server 订阅全量 MatchResult 流。
- 对每条 MatchResult，遍历 trades 和 finishOrders，按 `buyShardId` / `sellShardId` / `shardId` 过滤属于本 shard 的数据。
- 按 uid hash 路由到内部 settlement slot 处理。

> **注**：trading-server 结果共识的具体实现方案仍在设计中。详见 `openspec/changes/aeron-exchange/design.md` §12。

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
| **MatchResult** | 10 | orderReqOffset(i64), takerUid(i64), takerOrderId(i64), takerShardId(i32); **group trades**: index(i64), price(Decimal64), volume(Decimal64), buyUid(i64), sellUid(i64), buyOrderId(i64), sellOrderId(i64), buyShardId(i32), sellShardId(i32), takerOrderId(i64), takerUid(i64); **group finishOrders**: uid(i64), orderId(i64), status(FinishStatus), rejectReason(RejectReason), leaveAmount(Decimal64), leaveVolume(Decimal64), shardId(i32) |

**快照消息**（Cluster onTakeSnapshot / onLoadSnapshot）：

| 消息 | ID | 字段 |
|------|-----|------|
| **SnapshotHeader** | 20 | symbolId(u32), reqOffset(i64), orderCount(i32), nextSeq(i64) |
| **SnapshotBookOrder** | 21 | symbolId(u32), orderId(i64), uid(i64), shardId(i32), side(Side), price(Decimal64), volume(Decimal64), remainingVolume(Decimal64), amount(Decimal64), remainingAmount(Decimal64), seq(i64) |

### 5.2 枚举定义

| 枚举 | 编码 | 值 |
|------|------|-----|
| `Side` | uint8 | BUY(0), SELL(1) |
| `PriceType` | uint8 | LIMIT(0), MARKET(1), LIMIT_MAKER(2) |
| `TimeInForce` | uint8 | GTC(0), IOC(1), FOK(2) |
| `FinishStatus` | uint8 | COMPLETED(0), CANCEL(1), PART_CANCEL(2), EXCEPTION(3), REJECT(4), POST_ONLY_REJECT(5) |
| `RejectReason` | uint8 | NONE(0), INVALID_ORDER_ID(1), DUPLICATE_ORDER_ID(2), ORDER_EXPIRED(3), INVALID_PRICE_TYPE(4), INVALID_PRICE(5), PRICE_TICK_INVALID(6), INVALID_QUANTITY(7), INVALID_NOTIONAL(8), INVALID_TIME_IN_FORCE(9), POST_ONLY_WOULD_CROSS(10), FOK_NOT_FILLABLE(11), UNKNOWN(255) |

### 5.3 TradeOrder 字段约定

每条 TradeOrder 完整描述一次撮合事件的双方身份和角色：

- `index`：同一 match 内序号（0-based，每笔 fill 递增）。
- `orderReqOffset`：触发本次撮合的命令的 **Aeron Cluster log position**（全局单调递增）。`(orderReqOffset, index)` 唯一标识一笔成交。
- `price`、`volume`：成交价格与数量。
- `takerUid`、`takerOrderId`：taker 方。
- `buyUid`、`sellUid`、`buyOrderId`、`sellOrderId`：买卖双方。
- `buyShardId`、`sellShardId`：买卖方所属 shard（用于 trading-server 过滤）。

### 5.4 FinishOrder 字段约定

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
└──────────────────────────────────────────────────────────────────────────┘

  open-api (REST/JSON)
      │
      ▼
  ┌────────────────────────────────────────────────────────────────────┐
  │  trading-server shard N  (结果共识)                                  │
  │                                                                    │
  │  Leader:                                                           │
  │    · 消费 open-api 请求 (NEW_ORDER / CANCEL_ORDER / ...)           │
  │    · 消费 match-engine MDC 撮合结果 (ReplayMerge)                   │
  │    · 消费行情数据 (标记价 / 指数价)                                  │
  │    · 按 uid slot 结算 (更新账户/订单/持仓)                           │
  │    · 结果 → Follower (复制) + flush-service (落库)                  │
  │                                                                    │
  │  Follower:                                                         │
  │    · 应用 Leader 结果, 不执行命令, 不产生副作用                       │
  └──────────┬─────────────────────────────────────────────────────────┘
             │ Aeron Cluster Session           ▲ ReplayMerge
             │ PushOrderCommand (SBE)          │ MatchResult (SBE)
             │ CancelOrderCommand (SBE)        │
             ▼                                 │
  ┌────────────────────────────────────────────┴───────────────────────┐
  │  match-engine Cluster  (3 nodes, Raft 指令共识)                      │
  │                                                                    │
  │  Raft Log → onSessionMessage (单线程)                               │
  │    → engines.get(symbolId).process(cmd)                            │
  │    → MatchResult                                                   │
  │                                                                    │
  │  Egress:                                                           │
  │    localPub (IPC, 全节点) → Spy → Archive (本地录制)                 │
  │    mdcPub (UDP MDC, Leader only) → 网络多播                         │
  │                                                                    │
  │  Snapshot: onTakeSnapshot / onLoadSnapshot (SBE, Cluster 管理)      │
  └────────────────────────────────────────────────────────────────────┘
             │
             │ MDC (Dynamic control-mode)
             │ MatchResult (SBE)
             │
     ┌───────┼────────────────────────┐
     ▼       ▼                        ▼
  trading  trading               ┌─────────┐
  server   server               │  行情     │
  shard 0  shard 1  ...         │ service  │
  (ReplayMerge)                 │(ReplayMerge)
     │                          └─────────┘
     ▼
  ┌────────────────────┐
  │  flush-service     │
  │  → MySQL           │
  └────────────────────┘
```

---

## 10. 与当前代码的对应关系

### 10.1 核心模块

| 模块 | 当前状态 | Aeron 改造 |
|------|---------|-----------|
| `trading-protocol` | DTO + JSON ProtocolSerde | 新增 SBE schema，保留 DTO 参考 |
| `match-engine` | 多 slot + Kafka + ZK + Chronicle | → Aeron Cluster 单线程 + MDC + Archive |
| `tk-trading-server` | Kafka 消费 + ZK 选主 + Chronicle HA | → 结果共识（设计中） |
| `open-api` | REST → Kafka | REST → trading-server（接入方式待定） |
| `trading-flush` | 消费 Kafka trading_result | → 消费 trading-server 结算结果 |
| `message-dispatch` | 独立服务做 uid 扇出 | **删除**，逻辑移入 trading-server |

### 10.2 删除的依赖与组件

| 删除项 | 替代 |
|--------|------|
| Apache Kafka（核心路径） | Aeron Cluster + MDC |
| ZooKeeper（match-engine 选主） | Aeron Cluster Raft |
| Chronicle Queue（HA 文件队列） | Aeron Archive |
| LMAX Disruptor（match-engine 侧） | Cluster 单线程 |
| MatchSlot / MatchManager | ClusteredService |
| SnapshotFileHelper / SnapshotScheduleService | Cluster 快照 |
| message-dispatch 模块 | trading-server 内部过滤 |

### 10.3 保留不变的核心

- `MatchEngine`：process(OrderCommand) → MatchResult
- `OrderBook`：TreeMap + LinkedHashMap 订单簿
- `BookOrder`、`PriceLevel`：订单和价格档位
- Matcher 逻辑：LIMIT / MARKET / IOC / FOK / LIMIT_MAKER
- 持久化表结构：account / co_order / co_position / trade_order / transfer

---

## 11. 相关文档

- [市价单与交易对资产语义](doc/市价单与交易对资产语义.md)（BTC_USDT 示例：base/quote、市价 IOC 与业务锁仓、`OrderPayload` 字段；`MarketConfig.minTradableQuoteNotional`：剩余 quote 名义 **<** 阈值时业务完单）
- [Aeron 架构设计](openspec/changes/aeron-exchange/design.md)（match-engine Aeron Cluster 设计决策，SBE 协议定义，trading-server 结果共识方向）
