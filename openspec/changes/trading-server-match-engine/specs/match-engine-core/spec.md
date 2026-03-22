## ADDED Requirements

### Overview: Match-engine as state machine, protocol-sufficient implementation

The match-engine is specified as a **state machine**: each order has a lifecycle (e.g. active in book → partially filled → filled / cancelled). Commands `PUSH_ORDER` and `CANCEL_ORDER` drive state transitions; the engine emits `MatchResponse` (trades and finishOrders) as the observable output of those transitions.  
**Implementation note**: The protocol (OrderCommand, MatchResponse, TradeOrder, FinishOrder, and the DTO field contracts in the trading-protocol spec) is **self-contained**. A full match-engine can be implemented from this spec and the protocol alone, **without referencing existing code** in the repository.

### Requirement: Match-engine consumes order_req per symbol in order
The match-engine core SHALL consume commands for each trading symbol from a dedicated `order_req_(symbol)` stream in strict order.

#### Scenario: Commands are processed sequentially per symbol
- **WHEN** multiple `OrderCommand` messages arrive on `order_req_BTC_USDT`
- **THEN** the match-engine for BTC-USDT SHALL process them in the order they were received
- **AND** it SHALL NOT reorder or parallelize commands for the same symbol in a way that changes observable behavior

### Requirement: Match-engine maintains an in-memory order book
For each symbol, the match-engine core SHALL maintain an in-memory order book of active orders.

#### Scenario: New limit orders are added to the book
- **WHEN** a `PUSH_ORDER` command with a limit price is received and cannot be fully matched immediately at the current effective price
- **THEN** the match-engine SHALL add the order to the appropriate side of the order book (buy or sell)
- **AND** it SHALL preserve price-time priority within that side

#### Scenario: Cancel commands remove orders from the book
- **WHEN** a `CANCEL_ORDER` command is received for an order that is currently in the book
- **THEN** the match-engine SHALL remove that order from the book
- **AND** it SHALL reflect the terminal state via a `FinishOrder` in the next `MatchResponse`

### Requirement: Match-engine produces MatchResponse for matches and terminal orders
The match-engine core SHALL emit a `MatchResponse` whenever a command causes trades or orders to reach a terminal state.

#### Scenario: PUSH_ORDER triggers immediate trades
- **WHEN** a `PUSH_ORDER` command crosses the book and generates one or more trades
- **THEN** the match-engine SHALL emit a `MatchResponse` that includes:
  - the triggering order as `taker` (unless configured otherwise)
  - a `TradeOrder` entry for each trade generated
  - `FinishOrder` entries for any orders that become fully filled or otherwise terminal

#### Scenario: CANCEL_ORDER finalizes an order
- **WHEN** a `CANCEL_ORDER` command successfully cancels an active order
- **THEN** the match-engine SHALL emit a `MatchResponse` with a `FinishOrder` describing the cancelled order and its remaining quantity

### Requirement: Match-engine does not mutate external state directly
The match-engine core SHALL not mutate external account, position, or order storage directly.

#### Scenario: Match-engine is side-effect free outside its own state
- **WHEN** the match-engine processes any command
- **THEN** it SHALL only update its own in-memory order book and internal state
- **AND** it SHALL communicate results exclusively via `MatchResponse` messages on the configured output topic

### Requirement: Match-engine uses multi-slot parallelism per symbol hash
The match-engine process SHALL organize work into a fixed number of slots; each symbol SHALL be assigned to exactly one slot by `slotIndex = hash(symbol) % N`, so that all commands for the same symbol are processed by the same worker in order.

#### Scenario: Per-slot consumer and worker
- **WHEN** the match-engine runs
- **THEN** each slot SHALL have its own Kafka consumer (assigning that slot’s `order_req_(symbol)` topic partitions), a single queue, and a single worker thread
- **AND** the worker SHALL dequeue commands and route by symbol to a per-symbol MatchEngine (order book) within that slot
- **AND** there SHALL be no central consumer; each slot SHALL consume only its assigned topics

#### Scenario: Runtime add symbol (上币)
- **WHEN** `MatchManager.addSymbol(symbol)` is invoked (e.g. from config or admin)
- **THEN** the corresponding slot SHALL add the `order_req_(symbol)` topic to its assignment (e.g. via `addTopic`) and the consumer SHALL merge the new partition(s) into `assign(...)` so that the new topic is consumed without restart

### Requirement: Order-book snapshot and startup recovery
The match-engine SHALL support taking snapshots of the order book per symbol for startup recovery, and SHALL support restoring from snapshot so that consumption of `order_req_(symbol)` continues from the snapshot offset without replaying from the beginning.

#### Scenario: Snapshot trigger API
- **WHEN** a scheduled task or caller needs to trigger snapshots
- **THEN** the match-engine SHALL expose a way to obtain the list of symbols per slot (e.g. `getSymbolsBySlotIndex(slotIndex)`)
- **AND** it SHALL expose a way to submit a snapshot request per symbol (e.g. `submitSnapshotRequest(symbol)`), which SHALL enqueue the request on the same queue as order commands for that slot so that the snapshot is taken after all order_req messages up to that point are applied

#### Scenario: Snapshot file format
- **WHEN** a snapshot is written for a symbol
- **THEN** it SHALL be written to a configured shared-disk directory
- **AND** the file name SHALL be `{symbol}.{19-digit zero-padded offset}` (e.g. `BTC_USDT.0000000000000123456`), with symbol containing no `.`
- **AND** the first line SHALL be a single JSON object describing the order book: at least `offset`, `orderCount`; optionally `symbol`, `ts`
- **AND** each subsequent line SHALL be one JSON object per resting order with fields sufficient to restore (e.g. orderId, uid, shardId, side, price, remainingVolume, seq); empty book SHALL have only the first line with `orderCount=0`
- **Implementation note**: Internal `BookOrder` uses `volume`/`remainingVolume` (base) and, for market IOC semantics when applicable, `amount`/`remainingAmount` (quote budget or cumulative quote cap); resting limit orders typically omit quote fields.

#### Scenario: Restore from snapshot at startup
- **WHEN** the match-engine starts and finds snapshot file(s) in the snapshot directory for a symbol
- **THEN** it SHALL parse the file name to obtain (symbol, offset) and the first line to obtain offset and orderCount
- **AND** it SHALL parse order lines, sort them by seq ascending, and rebuild the order book by applying each order in that order (e.g. restoreOrder) without triggering matching
- **AND** it SHALL set the order book’s reqOffset to the snapshot offset and SHALL seek the consumer for `order_req_(symbol)` to that offset so that only incremental messages are processed

## Implementation notes (HA / master–slave consistency sampling)

- **Single-threaded order book per symbol**: For each symbol, the in-memory order book SHALL be read and mutated only on that symbol’s **slot worker thread** (no concurrent cross-thread access to the same `OrderBook`).
- **Sampling compares persisted queues only**: Optional master–slave consistency checks SHALL compare **Chronicle queue files already written to disk** under the configured `match.file-queue-dir` (`slave/{symbol}` vs `master/{symbol}`), not two in-memory order books; such checks SHALL be read-only on those files and **SHALL NOT** race the worker’s order-book mutations.

