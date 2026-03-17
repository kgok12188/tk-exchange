## 1. Core architecture and wiring

- [x] 1.1 Create or refactor `tk-trading-server` module entrypoint to use Spring Boot 3 (or existing base), wiring Kafka, Zookeeper/etcd clients, and configuration for `trading_(shard)` / `trading_result_(shard)`.
- [x] 1.2 Implement `InboundConsumer` that consumes `trading_(shard)` as a **single-threaded** Kafka consumer, parses JSON `{command, uid, data}`, and forwards messages to a `CommandRouter` interface.
- [x] 1.3 Implement `CommandRouter` that:
  - routes user-scoped commands (`NEW_ORDER`, `CANCEL_ORDER`, `MATCH`, `TRANSFER`, `CREATE_USER`, etc.) with `uid > 0` to `SlotQueue[slot(uid)]`.
  - broadcasts global commands (`UPDATE_MARK_PRICE`, `UPDATE_INDEX_PRICE`, etc.) to all `SlotQueue[i]`.

## 2. Slot model and per-uid state machine

- [x] 2.1 Introduce a configurable constant `SLOTS` (e.g., `trading.shard.slot-count`, default = CPU cores) and a routing function `slot(uid) = abs(hash(uid)) % SLOTS`.
- [x] 2.2 Implement `SlotWorker[i]` (0..SLOTS-1) each with:
  - a dedicated queue for `CommandMessage` (or equivalent),
  - a single processing thread that drains the queue and executes commands sequentially.
- [x] 2.3 Implement `SlotContext[i]` that maintains `Map<uid, UserTradingBook>` for that slot, and expose methods to:
  - load or create `UserTradingBook` for a given uid,
  - access `SettlementEngine` and `UserCommandHandler` for that slot.

## 3. UserTradingBook and settlement engine

- [x] 3.1 Implement `UserTradingBook` with:
  - primary maps for `orders`, `positions`, and `accounts`,
  - copy-on-write buffers (`changeOrders`, `changePositions`, `changeAccounts`) and corresponding remove-id sets,
  - `commit()` and `rollback()` semantics to atomically apply or discard changes.
- [x] 3.2 Implement `SettlementEngine` that:
  - takes a `TradingSettle{ uid, finishOrders[], tickets[] }` DTO (from `trading-protocol`),
  - updates orders based on `FinishOrder` (terminal states, leaveAmount/leaveVolume, margin release),
  - updates positions and realized PnL based on `Ticket` (open/close logic),
  - updates accounts for trade consideration, fees, and margin movements,
  - records all changes into an in-memory collection of `AsyncMessageItem`-like events.
- [x] 3.3 Implement a `UserCommandHandler` for non-matching commands (`CREATE_USER`, `NEW_ORDER`, `CANCEL_ORDER`, `TRANSFER`, etc.) that:
  - uses `UserTradingBook` to create/update/cancel orders and adjust accounts,
  - integrates with the same `AsyncMessageItem` event collection used by `SettlementEngine`.

## 4. Result publishing to trading_result_(shard)

- [x] 4.1 Implement `ResultPublisher` that:
  - is aware of the current instance role (`isMaster`),
  - for each slotIndex `i`, publishes its `AsyncMessageItem` batch only to partition `i` of `trading_result_(shard)`,
  - is a no-op when `isMaster == false`.
- [x] 4.2 For each processed command in a `SlotWorker`:
  - collect `AsyncMessageItem` events from `SettlementEngine` / `UserCommandHandler`,
  - on success, use `ResultPublisher` (if master) to emit them to Kafka,
  - on failure, rollback the corresponding `UserTradingBook` and discard the event batch.

## 5. Master/slave role management

- [ ] 5.1 Integrate a `MasterElection` component using Zookeeper/etcd (or reuse existing infra) to determine `isMaster` for each shard instance.
- [ ] 5.2 Ensure:
  - only master instances publish to `trading_result_(shard)`,
  - slave instances still consume `trading_(shard)` and maintain their `UserTradingBook` state.
- [ ] 5.3 Optionally implement a `ResultConsumer` on slave instances that:
  - consumes `trading_result_(shard)` from the master,
  - compares selected windows or samples of state/outputs with local computations,
  - logs metrics/errors on inconsistencies without mutating local state.

## 6. Integration and tests

- [ ] 6.1 Add integration tests or simulations that cover the end-to-end flow for key scenarios:
  - full fill, partial fill, cancel, limit/market/LIMIT_MAKER (POST_ONLY_REJECT),
  - open/increase/decrease/close positions, realized PnL, margin updates.
- [ ] 6.2 Verify per-uid ordering guarantees:
  - same uid commands are always processed in order within a single `SlotWorker`,
  - different uids may be processed in parallel across slots.
- [ ] 6.3 Verify that `trading_result_(shard)` is only written by master instances and that each slot only writes to its corresponding partition.

