# 交易系统架构说明

本文档根据项目架构图整理，描述分布式交易系统的组件、数据流与关键机制（消息队列、分区、风控、主从等）。

---

## 1. 整体概览

运行环境与基础框架：

- **JDK 版本**：21（`maven.compiler.source` / `target` = 21）。
- **Spring Boot 版本**：3.5.11（父 POM 继承自 `spring-boot-starter-parent:3.5.11`）。

系统负责：用户下单、交易逻辑处理、风控校验与数据持久化。通过 **消息队列（MQ）** 做异步与定序，通过 **分区（Sharding）** 做水平扩展，通过 **主从** 保证高可用与数据一致。

---

## 2. trading-server

trading-server 是系统的核心状态机，唯一输入源为 **trading_(分区)**，通过 **Zookeeper** 实现主从热备（仅主节点输出），按 uid 分槽顺序处理下单、结算、开平仓等指令。

→ 详见 [trading-server 详细设计](doc/trading-server.md)

---

## 3. 撮合引擎（match-engine）

match-engine 负责按币对独立撮合，唯一输入为 **order_req_(币对)**，严格顺序处理。支持限价单、市价单、LIMIT_MAKER（post-only），按 symbol 分片多线程并行撮合，输出 match_result_(币对)。

→ 详见 [撮合引擎详细设计](doc/match-engine.md)

---

## 4. 交易协议与持久化数据结构

定义 trading-server 与 match-engine 之间的消息协议（OrderCommand、MatchResponse、TradingSettle），以及 trading-server 需持久化的核心数据结构（账户、订单、持仓、成交、划转）。

→ 详见 [交易协议与持久化数据结构](doc/trading-protocol.md)

---

## 5. 风控服务

### 5.1 trading_result_sync

- 消费 **trading_result_(分区)**，写入 **数据持久化服务**。
- 持久化服务 **按版本写入最新数据**，保证版本一致。

### 5.2 ADL 服务（Auto-Deleveraging）

- 做 **风险校验**。
- 爆仓后盘口无法完全承接的仓位，进入 **对盘（ADL）** 处理。

### 5.3 风控检查（risk check）

- **触发**：用户持仓变更、标记价格变更时触发检查。
- **逻辑**：
  1. 风险率是否穿越强平线，判断用户是否可被强平。
  2. 若可强平，执行 **爆仓残值分配**。

### 5.4 相关 Topic

- **trading_(分区)**：ADL 请求写入对应用户所在分区；用户爆仓/移仓请求经 message_dispatch 或管理写入 trading_(分区)。
- **risk_(分区)**：定序后的风控主题。
- **quote**：标记价格 / 指数价格，供风控与 ADL 使用。

---

## 6. 行情服务（quote）

- 计算 **标记价格**。
- 计算 **指数价格**。
- 生成：
  - 交易线
  - 指数 K 线
  - 标记价格 K 线

---

## 7. 数据流小结

```
[open_api 下单] ─────────────────────────────────────────────────────────┐
[message_dispatch 成交] ─────────────────────────────────────────────────┼──▶ trading_(分区)
[行情 标记价/指数价] ─────────────────────────────────────────────────────┘            │
                                                                                      ▼
                    trading-server（状态机，主从热备，ZK 选主）
                    仅主节点输出 → response（按需）、trading_result_(分区)（多 partition，每 ringBuffer 对应一 partition）
                                                                                      ▼
                    trading_result_sync 消费 trading_result_(分区) → 数据持久化服务（按版本写入）
                                                                                      │
                    match-engine（按币对）→ match_result_(币对) → message_dispatch 分发给 trading_(分区)
```

---

## 8. 与本项目的对应关系（参考）

- **open_api**：写入 **trading_(分区)**（代码中 topic 命名用下划线，如 `trading_message_(分区)`）。
- **message_dispatch**：消费撮合结果（`match_result_(币对)`），按用户分区写入 **trading_(分区)**；当前仓库中 message_dispatch 仅占位，待实现。
- **行情**：标记价格、指数价格写入 **trading_(分区)** 或 quote，供 trading-server 状态机消费。
- **trading-server**：对应 `tk-trading-server`，消费 **trading_(分区)**（唯一输入），状态机处理，按 uid 分槽；**trading_result_(分区)** 为多 partition，**每个 ringBuffer 分别输出到各自对应的 partition**；主从由 Zookeeper 决定，仅主节点输出 trading_result_(分区)、response（按需）、orderReq_(币对)（按需）。
- **match-engine**：对应 `match-engine` 模块，**唯一输入**为 **orderReq_(币对)**，每个币对严格顺序处理，按币对撮合，输出 `match_result_(币对)`。
- **MQ Topic 命名**：统一使用**下划线**；如 trading_message_(分区)、trading_result_(分区)、match_result_(币对)、trade_price、response_message、recover_(币对) 等；与代码中 KafkaTopic 常量对应时需统一为下划线命名。

---

## 9. 完善架构图（与代码一致）

下图按 **trading_(分区) 唯一输入** 与 **主从仅主输出** 约定绘制；并标出**已实现**与**断点**（待补）。

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│               trading_(分区) 唯一输入 · 状态机主从热备（ZK 选主，仅主输出）                   │
└─────────────────────────────────────────────────────────────────────────────────────────┘

  写入 trading_(分区) 的三个来源:
  ┌─────────────┐   ┌─────────────────────┐   ┌─────────────┐
  │  open_api   │   │  message_dispatch   │   │   行情服务    │
  │  用户下单    │   │  成交信息按分区分发   │   │ 标记价/指数价 │
  └──────┬──────┘   └──────────┬──────────┘   └──────┬──────┘
         │                     │                     │
         └─────────────────────┼─────────────────────┘
                               ▼
               topic: trading_(分区)  (命名示例: trading_message_(分区))
                               │
                               │  唯一输入
                               ▼
  ┌─────────────────────────────────────────────────────────────────────────────────────┐
  │  tk-trading-server（状态机，主从热备，Zookeeper 选主）                                  │
  │  MessageQueueService 消费 trading_(分区) → MessageHandler 按 uid 槽位顺序处理          │
  │  · 仅主节点输出：trading_result_(分区)（多 partition，每 ringBuffer 对应一 partition）、response 与 orderReq_(币对)（按需）；从节点只处理不输出                            │
  │  · 分支: NEW_ORDER / CANCEL_ORDER / MATCH / TRANSFER / CREATE_USER / 价格更新          │
  │  · CREATE_USER → 已有；NEW_ORDER / CANCEL_ORDER / MATCH → 当前为空（断点 ①）            │
  └──────┬──────────────────────────────────────────────────────────────────────────────┘
         │
         │  断点 ① 需补：NEW_ORDER 通过后**按需**写 order_req_(币对)；MATCH 来自同分区（message_dispatch 已写入）
         ▼
  ┌─────────────────────────────────────────────────────────────────────────────────────┐
  │  match-engine (MatchEngine 协议撮合) 消费 order_req_(币对)，单币对严格顺序；产出 match_result_(币对)   │
  │  → message_dispatch 消费 match_result_(币对)，按 uid 分区分发到 trading_(分区)（断点 ②：待实现）   │
  └──────┬──────────────────────────────────────────────────────────────────────────────┘
         │
         │  trading_result_(分区) ← 仅主节点 sendToMq（多 partition，每个 ringBuffer 写各自 partition）；断点 ③：MATCH/NEW_ORDER 后需调用
         ▼
  ┌─────────────────────────────────────────────────────────────────────────────────────┐
  │  flush-service 的 DataSynchronizationService 消费 trading_result_(分区) → PersistenceService.flush 落库 (已有)   │
  └─────────────────────────────────────────────────────────────────────────────────────┘

  Topic 命名统一下划线，与代码对应:
  ┌────────────────────────────┬──────────────────────────────┐
  │ 文档/设计                  │ 实际 Topic 命名（下划线）      │
  ├────────────────────────────┼──────────────────────────────┤
  │ trading_(分区) 唯一输入   │ trading_message_(分区)      │
  │ orderReq_(币对) 唯一输入 │ order_req_(币对)            │
  │ match_result_(币对)        │ match_result_(币对)          │
  │ trading_result_(分区)    │ trading_result_(分区)       │
  │ 响应（按需）               │ response_message             │
  │ 最新价                     │ trade_price                  │
  │ 撮合恢复/对账              │ recover_(币对)              │
  └────────────────────────────┴──────────────────────────────┘
```

---

## 10. 实现步骤（按依赖顺序）

按当前代码现状，建议按以下顺序补齐链路；每一步都依赖前一步。

| 步骤 | 内容 | 说明 |
|------|------|------|
| **1** | **NEW_ORDER 接单与转发** | 在 `MessageHandler.handleUserMessage` 的 `NEW_ORDER` 分支：解析请求 → 从 RingBufferTradingBook 取/建 UserTradingBook → 校验（保证金、仓位、参数）→ 生成订单 ID、状态「待撮合」→ 更新 Book 内订单/账户（冻结保证金等）→ 可选：组装 AsyncMessageItem 调用 `userDataService.sendToMq` 写订单/账户变更到 **trading_result_(分区)**（本槽位对应 partition）；将**同一订单****按需**发往 **order_req_(币对)**，供 match-engine 严格顺序消费。 |
| **2** | **CANCEL_ORDER 撤单** | 在 `CANCEL_ORDER` 分支：查订单、校验可撤 → 更新订单状态为撤销、解冻保证金 → 若订单已进撮合引擎，需通过约定方式通知撮合侧（例如也发一条到 market 或单独 cancel topic）；若未进撮合，仅更新 Book 并可选 sendToMq。 |
| **3** | **match_result_(币对) → trading_(分区) + MATCH 处理** | **message_dispatch** 消费 **match_result_(币对)**，按 uid 分区分发到 **trading_(分区)**（待实现）。trading-server 在同一分区内收到 MATCH 消息后，在 **MessageHandler** 的 MATCH 分支：根据 TradeOrder 更新 UserTradingBook，组装 AsyncMessageItem，**仅主节点**调用 `userDataService.sendToMq` 写 **trading_result_(分区)**（本槽位对应 partition）。 |
| **4** | **response 回写** | 在 NEW_ORDER / CANCEL_ORDER 处理完成后，若有 DeferredResult，**按需**通过 response_message（或 open_api 已订阅的 response 机制）将结果回写到对应 reqId，避免前端一直超时。 |
| **5** | **一致性、幂等与恢复** | 对 MATCH：用 tradeId/orderId+orderReqOffset 做幂等，避免重复应用；对 trading_result_sync 的消费位点与 PersistenceService.flush 的失败重试、顺序保持一致；必要时用 recover_(币对) 与 DB 对账。 |
| **6** | **风控与强平** | 在持仓/标记价格更新后触发风控检查（见架构 §5）；强平单可走与 NEW_ORDER 类似路径或单独 topic，最终同样通过 MATCH / trading_result_(分区) 结算。 |

**依赖关系简述**：

- 步骤 1、2 不依赖 3；步骤 3 依赖 match-engine 已产出的 **match_result_(币对)**（已有）。
- 步骤 4 可与 1、2 并行，依赖 response_message 与 open_api 的订阅（已有）。
- 步骤 5、6 在 1–4 跑通后再完善即可。

**当前可复用**：open_api 写 trading_(分区)、MessageQueueService 按 uid 槽位消费、RingBufferTradingBook/UserTradingBook 结构、match-engine 全流程、flush-service 的 DataSynchronizationService 消费 trading_result_(分区) 落库、UserDataService.sendToMq 与 AsyncMessageItem 格式。**实现 NEW_ORDER/MATCH 及 sendToMq、response 时，需根据 Zookeeper 主从状态判断：仅主节点写入 trading_result_(分区)（多 partition，每 ringBuffer 写各自 partition）与 response（按需）、order_req_(币对)（按需）。所有 Kafka topic 命名统一使用下划线。**

---

## 11. 相关文档

- [trading-server 详细设计](doc/trading-server.md)（单一输入、分区与主从模型）
- [撮合引擎详细设计](doc/match-engine.md)（撮合流程、订单簿、分片模型、快照、主从高可用）
- [交易协议与持久化数据结构](doc/trading-protocol.md)（OrderCommand、MatchResponse、TradingSettle、持久化表结构）
- [市价单与交易对资产语义](doc/市价单与交易对资产语义.md)（BTC_USDT 示例：base/quote、市价 IOC 与业务锁仓、`OrderPayload` 字段；`MarketConfig.minTradableQuoteNotional`：剩余 quote 名义 **<** 阈值时业务完单）
