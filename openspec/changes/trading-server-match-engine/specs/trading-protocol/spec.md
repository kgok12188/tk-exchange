## ADDED Requirements

### Requirement: Unified trading protocol DTOs
The system SHALL define a set of shared DTOs for the trading pipeline so that `trading-server`, `match-engine`, and `message-dispatch` can integrate without sharing implementation details.

#### Scenario: Order commands are standardized
- **WHEN** `trading-server` sends a request to `match-engine`
- **THEN** it SHALL use an `OrderCommand` object with a `type` of either `PUSH_ORDER` or `CANCEL_ORDER`
- **AND** `PUSH_ORDER` SHALL include `id`, `uid`, `symbol` (or `marketId`), `side`, `priceType`, `price`, and either `volume` or `amount`
- **AND** `priceType` SHALL be one of: `LIMIT`, `MARKET`, `LIMIT_MAKER` (post-only)
- **AND** `CANCEL_ORDER` SHALL include at least `orderId`

#### Scenario: LIMIT_MAKER (post-only) orders are rejected when they would cross
- **WHEN** `match-engine` receives a `PUSH_ORDER` with `priceType` = `LIMIT_MAKER`
- **THEN** it SHALL NOT execute any trade on entry
- **AND** if the order would immediately match (buy price ≥ best ask, or sell price ≤ best bid), the system SHALL reject the entire order: do not add to the book, and SHALL emit a `FinishOrder` with a terminal status indicating post-only rejection (e.g. `POST_ONLY_REJECT`) and remaining quantity equal to the order size
- **AND** if the order would not cross, it SHALL be added to the book as a resting order (maker only); it may later be matched when another order takes liquidity

#### Scenario: Match results use MatchResponse
- **WHEN** `match-engine` produces any match or order-finalization event
- **THEN** it SHALL emit a `MatchResponse` containing:
  - a `taker` reference (nullable) identifying the triggering order
  - a list of `TradeOrder` entries describing matched trades
  - a list of `FinishOrder` entries describing orders that reached a terminal state

#### Scenario: TradingSettle is per-user
- **WHEN** `message-dispatch` consumes a `MatchResponse`
- **THEN** it SHALL construct one `TradingSettle` per affected user
- **AND** each `TradingSettle` SHALL contain all `FinishOrder` entries and `Ticket` entries relevant to that user only

### Requirement: TradeOrder contains full two-sided match information
Each `TradeOrder` SHALL fully describe both sides of a match and their roles in a single record.

#### Scenario: TradeOrder identifies taker and maker
- **WHEN** a trade is produced by the `match-engine`
- **THEN** the corresponding `TradeOrder` SHALL include `takerUid`, `makerUid`, and `takerOrderId`

#### Scenario: TradeOrder identifies buy and sell legs
- **WHEN** a trade is produced by the `match-engine`
- **THEN** the corresponding `TradeOrder` SHALL include `buyUid`, `sellUid`, `buyOrderId`, and `sellOrderId`
- **AND** it SHALL include `price`, `volume`, and a monotonic `index` within that match, plus the order_req offset (orderReqOffset; protocol field may be named `matchId`)
- **AND** orderReqOffset SHALL be the Kafka partition offset of the `order_req_(symbol)` message that triggered this match (all trades from the same command share this value)
- **AND** `index` SHALL be the 0-based sequence of the trade as the taker consumes liquidity (increments per fill: 0, 1, 2, …); together `(orderReqOffset, index)` uniquely identifies a trade leg

### Requirement: FinishOrder describes terminal order state
Each `FinishOrder` SHALL describe the final state of an order including remaining quantity.

#### Scenario: FinishOrder captures remaining quantity
- **WHEN** an order becomes fully filled, partially filled then cancelled, cancelled, or marked as exceptional
- **THEN** the corresponding `FinishOrder` SHALL include:
  - `uid` and `orderId`
  - a terminal `status` code (e.g. COMPLETED, CANCEL, PART_CANCEL, EXCEPTION, POST_ONLY_REJECT for post-only rejection)
  - a remaining `leaveAmount` or `leaveVolume` (zero for fully filled orders)

### Requirement: TradingSettle and Ticket support user-scoped settlement
`TradingSettle` and `Ticket` SHALL provide all information needed for `trading-server` to update a single user's state without consulting other users' events.

#### Scenario: Ticket identifies per-user trade contribution
- **WHEN** `message-dispatch` generates tickets from `TradeOrder` entries
- **THEN** each `Ticket` SHALL include:
  - `uid` and `orderId` for the user perspective
  - orderReqOffset (protocol field may be `matchId`), `index`, `price`, and `volume`
  - an `isTaker` flag indicating whether this user was the taker in the trade

#### Scenario: TradingSettle is sufficient for settlement
- **WHEN** `trading-server` receives a `TradingSettle` for a user
- **THEN** it SHALL be able to update that user's order lifecycle, positions, and account balances using only:
  - the `FinishOrder` entries in the `TradingSettle`
  - the `Ticket` entries in the `TradingSettle`
  - its own internal state and configuration (e.g. fee schedules, risk parameters)

