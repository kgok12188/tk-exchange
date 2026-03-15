## ADDED Requirements

### Requirement: Trading-server consumes TradingSettle per shard
The trading-server settlement component SHALL consume `TradingSettle` messages from `trading_(shard)` topics and treat them as the sole external input for user settlement.

#### Scenario: TradingSettle is processed for a user
- **WHEN** trading-server receives a `TradingSettle` for a specific uid on a shard
- **THEN** it SHALL load or reference that user's in-memory state for the shard
- **AND** it SHALL apply all `FinishOrder` and `Ticket` entries contained in the `TradingSettle` to update the user's orders, positions, and accounts

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

### Requirement: Trading-server emits trading_result for persistence and downstreams
After applying a `TradingSettle`, trading-server SHALL emit a summary of state changes on `trading_result_(shard)` for persistence and downstream services.

#### Scenario: State changes are flushed as AsyncMessageItem
- **WHEN** all entries in a `TradingSettle` have been applied for a user
- **THEN** trading-server SHALL package the resulting changes to orders, positions, accounts, and trades into one or more `AsyncMessageItem`-like records
- **AND** it SHALL publish these records to the appropriate `trading_result_(shard)` topic or partition for consumption by persistence and risk services

