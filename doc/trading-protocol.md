# 交易协议与持久化数据结构

> 本节从 [README.md](../README.md) §4 独立，详述 trading-server 与 match-engine 之间的交易协议及持久化数据结构。

---

## 4.1 trading-server 与 match-engine 的交易协议

trading-server 与 match-engine 之间通过 Kafka 传递订单与成交，约定如下。

**（1）trading-server → match-engine（下单/撤单）**

- **Topic**：`order_req_(币对)`（如 order_req_BTC_USDT）。同一币对严格顺序。
- **消息指令**：每条消息为一条**指令**，指令类型分为：
  - **PUSH_ORDER**：将订单送入撮合队列。消息体为 **Order**（或等价 JSON）。必填字段：`id`、`uid`、`marketId`/`symbol`、`side`（BUY/SELL）、`priceType`、`timeInForce`（GTC/IOC/FOK）、`clientOrderId`（可选）、`price`、`volume` 或 `amount`。**priceType** 取值：**LIMIT**（限价，先撮合再挂簿）、**MARKET**（市价，只撮合不挂簿）、**LIMIT_MAKER**（post-only，只挂簿不撮合；若会立即成交则整单拒绝并返回 POST_ONLY_REJECT）。由 trading-server 置为「待撮合」，match-engine 不修改订单 ID，仅产出成交/终态。
  - **CANCEL_ORDER**：撤销指定订单。消息体需包含被撤订单标识（如 `orderId`，可选 `uid`）；match-engine 从盘口移除该订单并可在 MatchResponse 的 finishOrders 中回写终态。
- **语义**：同一 topic 上仅通过指令类型区分「下单」与「撤单」，顺序严格按分区保证。

**（2）match-engine → message-dispatch → trading-server（成交回写）**

- **Topic**：match-engine 产出 **MatchResponse** 写入 `match_result_(币对)`。
- **MatchResponse** 包含三部分：
  - **taker**：谁触发了本次成交/撤单。可为 taker 订单的 `orderId`（或 `uid + orderId`）；若无入单触发（仅行情吃掉 maker）则为 `null` 或约定 sentinel（如 orderId=0）。
  - **trades**：**List&lt;TradeOrder&gt;**，本笔触发的成交明细。
  - **finishOrders**：**List&lt;FinishOrder&gt;**，本笔中进入终态的订单（已完全成交、部分成交撤销、已撤销、异常等）。
- **message-dispatch**：消费 MatchResponse，**按 uid 拆分**为每用户一份 **TradingSettle**，再按 uid 分区分发到 `trading_(分区)`，供 trading-server 消费。
- **TradingSettle**（每用户结算包）：包含
  - **List&lt;FinishOrder&gt;**：该用户在本笔中进入终态的订单。
  - **List&lt;Ticket&gt;**：该用户在本笔中的成交明细（按用户视角拆开后的「票」）。
- **Ticket 字段约定**：`index`、`orderReqOffset`（即 order_req offset，协议中可为 matchId）、`price`、`volume`、`uid`、`order_id`、`is_taker`（该用户是否为 taker）。
- **TradeOrder 字段约定**（match-engine 产出）：`index`（同一 match 内序号）、`orderReqOffset`（即 order_req offset，协议中可为 matchId）、`price`、`volume`、`taker_uid`、`maker_uid`、`buy_uid`、`sell_uid`、`buy_order_id`、`sell_order_id`、`taker_order_id`
- **FinishOrder 约定**：至少包含 `uid`,`orderId` 与终态 `status`（如 COMPLETED/CANCEL/PART_CANCEL/EXCEPTION/**POST_ONLY_REJECT**）、**未成交的 amount 或 volume**（如 `leaveAmount`/`leaveVolume`）；POST_ONLY_REJECT 表示 LIMIT_MAKER 单因会立即成交被整单拒绝。
- **语义**：trading-server 收到的是一用户一包的 TradingSettle（FinishOrders + Tickets），据此更新该用户订单状态、持仓、账户，并持久化（见 4.2）。原 `messageType`（MATCH_ORDER/EXCEPTION_ORDER/LIQ_ORDER）可保留为可选或废弃，由实现决定。

**（3）一对一与顺序**

- 每个币对：`order_req_(币对)` 与 `match_result_(币对)` 中该币对部分 **一一对应**；同币对消息严格顺序。

---

## 4.2 trading-server 需持久化的数据结构

trading-server 通过 **trading_result_(分区)** 输出内存变更，由 **trading_result_sync** 消费并落库。需持久化的核心数据结构与表对应如下。

| 逻辑实体 | 表名 | 主要字段（概要） | 用途 |
|----------|------|------------------|------|
| **账户** | `account` | uid, coin_id, coin_name, available_balance, cross_margin_frozen, isolated_margin_frozen, order_frozen, txid, ctime, mtime | 用户各币种可用、冻结保证金、挂单冻结；txid 做版本/乐观锁 |
| **订单** | `co_order` | id, uid, position_id, symbol, market_id, amount, volume, price_type, price, status, open, side, position_type, margin, deal_volume, deal_amount, avg_deal_price, fee, leverage_level, realized_amount, cancel_order, txid, ctime, mtime, completed_time, cancel_time | 委托单生命周期与成交信息；status：0 初始化 1 部分成交 2 完全成交 3 部分成交撤销 4 撤销 5 异常 |
| **持仓** | `co_position` | id, uid, symbol, market_id, volume, close_volume, pending_close_volume, fee, open_price, close_price, hold_amount, realized_amount, status, leverage_level, side, position_type, liq_order_id, txid, ctime, mtime | 用户某合约多/空持仓、保证金、已实现盈亏；status：1 未完成 0 已完成 |
| **成交** | `trade_order` | id, match_id, price, volume, status, full_match, **index**（同一 match 内序号）, **taker_uid, maker_uid, buy_uid, sell_uid, buy_order_id, sell_order_id**（无 role）；ctime, mtime；唯一 (order_id, match_id) 由 buy_order_id/sell_order_id 与 match_id 表达；**每条成交含买卖两方与 taker/maker 身份**（见 4.1 TradeOrder 约定） | 单笔成交记录；match-engine 产出，trading-server 写入 trading_result 后落库 |
| **划转** | `transfer` | id, uid, transfer_id, coin_id, amount, txid, status, type, ctime, mtime | 入金/出金流水；与账户变动一致 |

**说明**：

- **txid**：各表用于顺序或乐观控制的版本/序号，与 trading_result 消息顺序一致。
- **写入路径**：trading-server 状态机产生 ACCOUNT / ORDER / POSITION / TRADE_ORDER / TRANSFER 等 **AsyncMessageItem**，发往 trading_result_(分区)；trading_result_sync 消费后按类型调用 **PersistenceService.flush** 落库（insert/update 由实现决定）。
- **配置类**（trading-server 只读或通过管理写入）：`user`（uid, status, group_name）、`market_config`（交易对、费率、撮合类型等）、`coin`（币种）—— 不列入「trading-server 产出」的持久化，但为运行依赖。
