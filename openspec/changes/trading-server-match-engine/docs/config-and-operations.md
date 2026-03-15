# Trading protocol pipeline – configuration and operations

## Architecture overview

- **Match-engine** is a **state machine**: orders move through states (e.g. in-book, partially filled, filled, cancelled) driven by `OrderCommand` (PUSH_ORDER / CANCEL_ORDER); each transition may produce a `MatchResponse` (trades + finishOrders).
- **Protocol is self-contained**: the DTOs and topic contracts defined in this change are sufficient to implement the match-engine from scratch; **no need to reference existing code** for core matching behavior.

## Topic names

| Topic pattern | Direction | Description |
|---------------|-----------|-------------|
| `order_req_(symbol)` | trading-server → match-engine | OrderCommand (PUSH_ORDER / CANCEL_ORDER). Example: `order_req_BTC_USDT`. |
| `match_result_(symbol)` | match-engine → message-dispatch | MatchResponse. Example: `match_result_BTC_USDT`. |
| `trading_(shard)` | message-dispatch → trading-server | TradingSettle per user. Example: `trading_0`, `trading_1`. |
| `trading_result_(shard)` | trading-server → persistence/risk | AsyncMessageItem. Example: `trading_result_0`. |

Symbol format: use underscores in topic names (e.g. `BTC_USDT`). Shard index: integer `0` to `N-1`.

## TradeOrder: orderReqOffset and index

- **orderReqOffset**（协议字段名可为 matchId）：order_req 的 Kafka partition offset，即触发本笔撮合的那条 `order_req_(symbol)` 的 offset。同一 order_req 产生的所有成交共享同一 orderReqOffset（用于幂等与顺序）。
- **index**: 0-based monotonic sequence of the trade as the taker consumes liquidity (first fill = 0, second = 1, …). Together `(orderReqOffset, index)` uniquely identifies a trade leg.

## Configuration

- **match-engine**: `bootstrapServers`, list of symbols (each gets `order_req_(symbol)` and `match_result_(symbol)`), and a `KafkaProducer` for writing MatchResponse.
- **message-dispatch**: `bootstrapServers`, `matchResultTopics` (e.g. `["match_result_BTC_USDT"]`), `shardCount` (e.g. `4`), and a `KafkaProducer` for writing TradingSettle to `trading_0` … `trading_{shardCount-1}`.
- **trading-server**: one `TradingSettleConsumer` per shard; each consumes `trading_{shardId}` and writes to `trading_result_{shardId}`. Needs `bootstrapServers` and a `KafkaProducer`.

## Operational notes

- **Ordering**: Per-symbol and per-shard ordering is required. Use single partition per `order_req_(symbol)` and per `trading_(shard)` (or partition by key consistently).
- **Monitoring**: Track consumer lag on `order_req_*`, `match_result_*`, and `trading_*`; alert on producer errors and deserialization failures.
- **Error handling**: Invalid JSON or unknown command type should be logged and skipped (at-least-once). Idempotency is via `orderReqOffset` (matchId in protocol) + `index` and order/position versioning downstream.
- **Schema version**: Protocol DTOs carry optional `schemaVersion` (see `ProtocolVersion.CURRENT`). When evolving the protocol, support the current and previous version during rollout.
