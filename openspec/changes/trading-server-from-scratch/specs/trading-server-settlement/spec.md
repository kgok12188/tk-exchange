## ADDED Requirements

### Requirement: Trading-server consumes TradingSettle per shard

The trading-server settlement component SHALL consume `TradingSettle` messages from `trading_(shard)` topics and treat them as the sole external input for user settlement (other command types MAY share the same topic but SHALL NOT bypass this input path).

#### Scenario: TradingSettle is processed for a user

- **WHEN** trading-server receives a `TradingSettle` for a specific uid on a shard
- **THEN** it SHALL load or reference that user's in-memory state for the shard
- **AND** it SHALL apply all `FinishOrder` and `Ticket` entries contained in the `TradingSettle` to update the user's orders, positions, and accounts

### Requirement: Unified JSON envelope `{command, uid, data}` for trading_(shard)

All messages on `trading_(shard)` that are consumed by trading-server SHALL follow a unified JSON envelope:

```json
{
  "command": "XXX",
  "uid": 123456,
  "data": { }
}
```

- `command` is a required string enumerating the instruction type.
- `uid` is required for user-scoped commands (e.g., `CREATE_USER`, `NEW_ORDER`, `CANCEL_ORDER`, `MATCH`, `TRANSFER`) and optional/unused for global commands (e.g., `UPDATE_MARK_PRICE`, `UPDATE_INDEX_PRICE`).
- `data` holds the command-specific payload, whose DTOs are defined in `trading-protocol` (e.g., `TradingSettle`, `NewOrderRequest`, `CancelOrderRequest`).

#### Scenario: User-scoped trading command

- **WHEN** a message with `command = "MATCH"` and a positive `uid` is received
- **THEN** trading-server SHALL:
  - use `uid` to determine the processing slot (see slotting requirement below)
  - deserialize `data` into a `TradingSettle` DTO
  - apply the `TradingSettle` to that user's `UserTradingBook` in the chosen slot

### Requirement: Per-uid slotting and single-threaded processing per slot

Trading-server SHALL implement a slot-based concurrency model to ensure per-user serial execution and cross-user parallelism.

#### Scenario: Slot assignment for user commands

- **GIVEN** a configured slot count `SLOTS`
- **AND** a pure function `slot(uid) = abs(hash(uid)) % SLOTS`
- **WHEN** a message with a positive `uid` is received on `trading_(shard)`
- **THEN** trading-server SHALL:
  - compute `slotIndex = slot(uid)`
  - enqueue the command into the queue associated with `slotIndex`
  - process all commands queued for `slotIndex` in a single-threaded, FIFO manner
- **AND** all commands for the same `uid` SHALL always be routed to the same `slotIndex`, preserving per-user ordering.

#### Scenario: Global/broadcast commands

- **WHEN** a global command (e.g., `UPDATE_MARK_PRICE`, `UPDATE_INDEX_PRICE`) with no `uid` is received
- **THEN** trading-server SHALL enqueue a corresponding task into **each** slot queue
- **SO THAT** all slots observe the global event and can update any shared or per-user derived state (e.g., mark price caches).

### Requirement: FinishOrder drives order lifecycle transitions

FinishOrder entries SHALL drive the lifecycle transitions of orders within trading-server.

#### Scenario: Order transitions to a terminal state

- **WHEN** a `FinishOrder` with a given `orderId` and `status` is processed
- **THEN** trading-server SHALL transition the corresponding in-memory order state to the terminal status specified
- **AND** it SHALL update the order's remaining quantity using `leaveAmount` or `leaveVolume`
- **AND** it SHALL release or adjust any associated frozen margin or balance according to the new state

### Requirement: Tickets drive position and balance updates

Ticket entries SHALL be used to update user positions and account balances.

#### Scenario: Ticket updates position and realized PnL

- **WHEN** a `Ticket` is processed for a user
- **THEN** trading-server SHALL:
  - determine whether the ticket represents an opening or closing trade based on existing positions and side
  - adjust the user's position volume and average entry/exit price accordingly
  - compute and apply any realized profit or loss for closing trades

#### Scenario: Ticket updates account balances

- **WHEN** a `Ticket` is processed for a user
- **THEN** trading-server SHALL update the user's account balances to reflect:
  - debits or credits for trade consideration (price × volume)
  - any fees or funding adjustments defined by configuration
  - movement between available balance, frozen margin, and order frozen amounts as appropriate

### Requirement: UserTradingBook as the single in-memory source of truth per user

Trading-server SHALL maintain, for each user in a shard, an in-memory `UserTradingBook` that represents the user's trading state on that shard.

#### Scenario: Loading and updating UserTradingBook

- **WHEN** a command with a positive `uid` is processed in a given slot
- **THEN** trading-server SHALL:
  - load or create the corresponding `UserTradingBook` for that `uid` and shard
  - apply all mutations for that command to the `UserTradingBook` within a single logical transaction (using copy-on-write or equivalent)
  - either commit all changes atomically or rollback them entirely in case of error

### Requirement: Trading-server emits trading_result for persistence and downstreams

After applying a `TradingSettle` or other state-modifying user command, trading-server SHALL emit a summary of state changes on `trading_result_(shard)` for persistence and downstream services.

#### Scenario: State changes are flushed as AsyncMessageItem

- **WHEN** all entries in a `TradingSettle` or a user command have been successfully applied for a user
- **THEN** trading-server SHALL package the resulting changes to orders, positions, accounts, and trades into one or more `AsyncMessageItem`-like records
- **AND** it SHALL publish these records to the appropriate `trading_result_(shard)` topic
- **AND** each slot `i` SHALL publish only to partition `i` of `trading_result_(shard)`.

### Requirement: Master-only output with instance-level HA

Trading-server SHALL support instance-level high availability via a master/slave model.

#### Scenario: Master instance behavior

- **GIVEN** that a trading-server instance is elected as master for a shard (via Zookeeper or equivalent)
- **WHEN** it processes commands from `trading_(shard)`
- **THEN** it SHALL:
  - execute the settlement state machine
  - publish `AsyncMessageItem` events to `trading_result_(shard)` (partitioned by slot index)

#### Scenario: Slave instance behavior

- **GIVEN** that a trading-server instance is running as slave for a shard
- **WHEN** it processes commands from `trading_(shard)` and (optionally) consumes `trading_result_(shard)` produced by the master
- **THEN** it SHALL:
  - maintain an in-memory mirror of the settlement state (using the same state machine logic)
  - NOT publish any `trading_result_(shard)` events
  - MAY compare its local state or derived outputs against the master output for selected windows or samples, emitting metrics/logs for inconsistencies

