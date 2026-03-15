## ADDED Requirements

### Requirement: Message-dispatch consumes MatchResponse from match-engine
The message-dispatch service SHALL consume `MatchResponse` messages produced by the match-engine for each symbol.

#### Scenario: MatchResponse stream is subscribed per symbol
- **WHEN** the system is configured for a symbol such as BTC-USDT
- **THEN** message-dispatch SHALL subscribe to the corresponding `match_result_BTC_USDT` (or equivalent) topic
- **AND** it SHALL receive every `MatchResponse` emitted by the match-engine for that symbol

### Requirement: Message-dispatch builds per-user TradingSettle
Message-dispatch SHALL construct one `TradingSettle` per affected user from each `MatchResponse`.

#### Scenario: Multiple users participate in a match
- **WHEN** a `MatchResponse` contains trades involving multiple users (as buyers and sellers)
- **THEN** message-dispatch SHALL:
  - identify all distinct uids appearing in `trades` and `finishOrders`
  - build a `TradingSettle` for each uid that includes only that user's `FinishOrder` entries and `Ticket` entries

### Requirement: Tickets are derived from TradeOrder entries
Message-dispatch SHALL derive `Ticket` entries from `TradeOrder` entries for each user.

#### Scenario: One TradeOrder yields tickets for both sides
- **WHEN** a `TradeOrder` includes both a buyer and a seller
- **THEN** message-dispatch SHALL create:
  - a `Ticket` for the buyer with `uid = buyUid`, `orderId = buyOrderId`, and `isTaker` set according to whether `buyUid` equals `takerUid`
  - a `Ticket` for the seller with `uid = sellUid`, `orderId = sellOrderId`, and `isTaker` set according to whether `sellUid` equals `takerUid`
- **AND** both tickets SHALL share the same orderReqOffset (protocol field may be `matchId`), `index`, `price`, and `volume` values from the `TradeOrder`

### Requirement: TradingSettle is routed by uid to trading_(shard)
Message-dispatch SHALL route each `TradingSettle` to a shard-specific `trading_(shard)` topic based on the user id.

#### Scenario: Deterministic uid-to-shard mapping
- **WHEN** message-dispatch prepares to send a `TradingSettle` for a given uid
- **THEN** it SHALL compute a deterministic shard identifier from the uid (for example, `uid mod N`)
- **AND** it SHALL publish the `TradingSettle` to the corresponding `trading_(shard)` topic or partition according to the configured sharding strategy

