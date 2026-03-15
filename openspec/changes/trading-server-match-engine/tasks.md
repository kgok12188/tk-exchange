## 1. Protocol and DTO module

- [x] 1.1 Define trading protocol module structure (e.g., `trading-protocol`) and shared DTO classes (`OrderCommand`, `OrderPayload`, `CancelPayload`, `MatchResponse`, `TradeOrder`, `FinishOrder`, `TradingSettle`, `Ticket`, `TakerRef`).
- [x] 1.2 Add serialization configuration for protocol DTOs (JSON/Avro), including schema versioning where needed.

## 2. Match-engine core

- [x] 2.1 Implement per-symbol `MatchEngine` component that consumes `order_req_(symbol)` and processes `OrderCommand` messages in order.
- [x] 2.2 Implement in-memory order book (price-time priority) with insertion, cancellation, and matching in `com.tk.match.core.OrderBook`.
- [x] 2.3 Implement matching logic to generate `TradeOrder` and `FinishOrder` collections from `PUSH_ORDER` and `CANCEL_ORDER` commands.
- [x] 2.4 Implement `MatchResponse` producer to emit results to `match_result_(symbol)` topics.

## 3. Message-dispatch routing

- [x] 3.1 Implement consumer for `MatchResponse` streams per symbol in message-dispatch.
- [x] 3.2 Implement transformation logic from `TradeOrder` and `FinishOrder` to per-user `TradingSettle` objects (including `Ticket` generation).
- [x] 3.3 Implement uid-to-shard routing and producer logic to send `TradingSettle` to `trading_(shard)` topics.

## 4. Trading-server settlement

- [x] 4.1 Implement shard-based consumer in trading-server to receive `TradingSettle` messages from `trading_(shard)` topics.
- [x] 4.2 Implement `UserTradingBook` or equivalent component to apply `FinishOrder` and `Ticket` events to in-memory orders, positions, and accounts.
- [x] 4.3 Implement logic to package state changes into `trading_result_(shard)` messages (e.g., `AsyncMessageItem`-style payloads) for persistence and downstream services.

## 5. Integration and verification

- [x] 5.1 Add integration tests or simulations that run the full pipeline (`OrderCommand` → `MatchResponse` → `TradingSettle` → `trading_result`) for basic scenarios (full fill, partial fill, cancel, exception).
- [x] 5.2 Document configuration options (topic names, shard counts, symbol mappings) and operational considerations (monitoring, metrics, error handling).

## 6. Match-engine snapshot and recovery (per 架构 3.6.7)

- [ ] 6.1 Expose `MatchManager.getSymbolsBySlotIndex(int)` and `MatchManager.submitSnapshotRequest(String symbol)`; implement snapshot request enqueue and worker branch to take snapshot (serialize OrderBook, write to shared disk).
- [ ] 6.2 Implement snapshot file format: first line JSON (offset, orderCount, symbol, optional ts), subsequent lines one JSON per order (orderId, uid, shardId, side, price, remainingVolume, seq); filename `{symbol}.{19-digit offset}`.
- [ ] 6.3 Implement OrderBook traversal to output resting orders and `restoreOrder(...)` to rebuild book without matching; implement startup scan of snapshot dir, parse file, sort by seq, restore, then seek consumer to snapshot offset.
- [ ] 6.4 Add scheduled task (e.g. cron) that calls getSymbolsBySlotIndex for each slot and submitSnapshotRequest for each symbol; configure snapshot directory (e.g. match.snapshot.dir).

## 7. 主从文件队列抽样比对（状态机一致性校验，见 design §9）

- [ ] 7.1 实现抽样比对逻辑：按 symbol 分别从 baseDir/slave 与 baseDir/master 取最后一条记录的 orderReqOffset（lastSlave、lastMaster），对齐点 end = min(lastSlave, lastMaster)，在区间 (end - N, end] 内倒推 N 条，逐 orderReqOffset 比较两侧 payload。
- [ ] 7.2 不一致时打 error 日志（含 symbol、orderReqOffset、差异摘要），便于人工介入和排查；仅读队列与比对，不修改队列与主从状态。
- [ ] 7.3 以定时任务或独立比对线程方式触发比对（可配置间隔与 N）。

