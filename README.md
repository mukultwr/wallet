# Keychain OS — Prepaid Wallet Service

Java 21 · Spring Boot 3.2 · PostgreSQL · MongoDB · Redis (Redisson)

---

## Quick Start

```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Run the service
./mvnw spring-boot:run

# 3. Create a wallet
curl -s -X POST http://localhost:8080/wallets \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-1","name":"Rahul Sharma","email":"rahul@example.com","phone":"+919999999999"}'

# 4. Top up ₹500
curl -s -X POST http://localhost:8080/wallets/<id>/topup \
  -H "Content-Type: application/json" \
  -d '{"amount":500}'

# 5. Deduct ₹100 (order placement)
curl -s -X POST http://localhost:8080/wallets/<id>/deduct \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ORDER-001" \
  -d '{"referenceId":"ORDER-001"}'

# 6. Check balance
curl -s http://localhost:8080/wallets/<id>/balance

# 7. Run the Order Service stub
pip install requests
python order-stub/order_stub.py <wallet-id> --orders 3
python order-stub/order_stub.py <wallet-id> --orders 5 --concurrent
python order-stub/order_stub.py <wallet-id> --idempotency-test
```

---

## API Reference

| Method | Path | Header | Purpose |
|--------|------|--------|---------|
| POST | `/wallets` | — | Create wallet |
| POST | `/wallets/:id/topup` | — | Add funds (body: `{"amount": 500}` in INR) |
| POST | `/wallets/:id/deduct` | `Idempotency-Key: <order_id>` | Deduct ₹100 |
| GET | `/wallets/:id/balance` | — | Current balance |
| GET | `/wallets/:id/transactions` | — | Full ledger (newest first) |

---

## Key Design Decisions

### 1. Money stored as `BIGINT` (paise), never `FLOAT`

₹100 = `10000` paise. Floating-point arithmetic silently corrupts financial
data at scale (`0.1 + 0.2 ≠ 0.3` in IEEE-754). All internal calculations use
`long`; the API converts to/from `BigDecimal` only at the boundary.

### 2. Two-database split

| Store | What lives there | Why |
|-------|-----------------|-----|
| **MongoDB** | Wallet profile (name, email, phone, status) | Flexible schema; user attributes change shape over time |
| **PostgreSQL** | Balances + ledger + outbox | ACID guarantees; row-level locking; `CHECK (balance >= 0)` as a hard DB-level guard |

The balance is always the PostgreSQL source of truth. MongoDB is never
consulted for financial decisions.

### 3. Concurrency — two-layer locking

**Layer 1 — Redisson distributed lock** (`lock:wallet:<id>`, 10 s max hold):
Serialises deductions across all service instances before touching the DB.

**Layer 2 — PostgreSQL `SELECT FOR UPDATE`** inside the JPA transaction:
Safety net that holds even if the app lock expires or a script bypasses the
service entirely. If both layers are somehow bypassed, the `CHECK (balance >= 0)`
constraint in PostgreSQL is the last line of defence.

This three-layer approach means the system is correct whether you have one pod
or one hundred.

### 4. Idempotency — two-layer deduplication

**Layer 1 — Redis cache** (`idempotency:<key>`, 24 h TTL):
O(1) short-circuit. The response from the first successful deduction is
serialised and cached; retries return the exact same JSON without touching
PostgreSQL.

**Layer 2 — DB `UNIQUE` constraint** on `transactions.idempotency_key`:
Guards against cache eviction. If the Redis entry has been purged and a retry
arrives after 24 h, the DB insert will violate the unique constraint, which the
service catches and resolves by looking up the original transaction.

The `Idempotency-Key` header is **required** on `POST /deduct`. This mirrors
the Stripe API design: the caller (Order Service) owns the key, which is
naturally the `order_id`.

### 5. Immutable append-only ledger

Transactions are never updated or deleted. Every row stores `balance_after` as
a snapshot, so the full audit trail can be reconstructed at any point, and any
discrepancy between the balance row and the sum of ledger entries is detectable.

### 6. Outbox pattern (ready for Kafka)

Every wallet mutation writes an `outbox_events` row atomically in the same DB
transaction. When you need async event publishing (notify Order Service,
trigger notifications, fraud checks), wire up a Debezium CDC connector or a
polling publisher — zero changes to the wallet service code.

### 7. Wallet status lifecycle

`ACTIVE → SUSPENDED → CLOSED`. Deductions and top-ups are rejected on non-ACTIVE
wallets. This gives ops a handle to freeze wallets for fraud without deleting
data or changing financial logic.

---

## Data Model

```
MongoDB: wallets
  { _id, customerId, name, email, phone, status, createdAt, updatedAt }

PostgreSQL: wallet_balances
  wallet_id PK | balance BIGINT CHECK(>=0) | updated_at

PostgreSQL: transactions (append-only)
  id PK | wallet_id | type | amount | balance_after | idempotency_key UNIQUE
        | reference_id | status | created_at

PostgreSQL: outbox_events
  id PK | wallet_id | event_type | payload | published | created_at
```

---

## Testing Methodology

**Unit tests** (`WalletServiceTest`) mock all external dependencies and verify
business logic in isolation:
- Balance constraint at exact boundary (₹100 / ₹99.99)
- Idempotency cache hit short-circuits without acquiring the lock
- Mongo compensation on PostgreSQL failure during wallet creation
- Suspended wallet rejection

**Integration tests** (`WalletControllerIntegrationTest`) use Testcontainers
(real PostgreSQL, MongoDB, Redis) and test the full HTTP stack:
- Full happy-path flow (create → top-up → deduct → balance)
- Exact-₹100 boundary succeeds; ₹99 fails
- Idempotency: same `Idempotency-Key` twice returns same `transactionId`
- **Race condition test**: 5 concurrent threads deduct from a ₹200 wallet — exactly 2 succeed, balance never goes negative
- Missing `Idempotency-Key` header → 400
- Unknown wallet → 404

The race-condition test is the most important: it directly validates that the
distributed lock + `SELECT FOR UPDATE` combination prevents over-spend under
concurrent load.

---

## What I Would Do With More Time

| Priority | Item |
|----------|------|
| High | Pagination on `GET /transactions` (cursor-based, not offset) |
| High | Outbox relay publisher wired to Kafka |
| High | Wallet suspension / admin API |
| Medium | Configurable deduction amount (not always ₹100) |
| Medium | Prometheus metrics: deduction latency, lock wait time, cache hit rate |
| Medium | Rate limiting per wallet per minute (Redis token bucket) |
| Medium | Partial top-up validation (e.g., minimum ₹10) |
| Low | MongoDB → Postgres replica for read-heavy balance queries |
| Low | Soft-delete and GDPR data erasure for wallet profiles |
| Low | OpenAPI / Swagger docs auto-generated from the controller |
| Scale | Partition `transactions` table by `wallet_id` range when rows > 100 M |
| Scale | Read replica for `GET /balance` and `GET /transactions` |
