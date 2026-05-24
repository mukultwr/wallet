# Keychain OS — Prepaid Wallet Service

Java 21 · Spring Boot 3.2 · PostgreSQL · MongoDB · Redis (Redisson)

**Live deployment:** https://wallet-service-production-7710.up.railway.app

---

## Quick Start

```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Run the service
./mvnw spring-boot:run

# 3. Get tokens (run once — three roles)
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"customerId":"ADMIN-1","role":"ADMIN"}' | jq -r '.token')

SERVICE_TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"customerId":"ORDER-SERVICE","role":"SERVICE"}' | jq -r '.token')

CUSTOMER_TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-1","role":"CUSTOMER"}' | jq -r '.token')

# 4. Create a wallet (ADMIN only)
curl -s -X POST http://localhost:8080/wallets \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"customerId":"CUST-1","name":"Rahul Sharma","email":"rahul@example.com","phone":"+919999999999"}'

# 5. Top up ₹500 (SERVICE or ADMIN)
curl -s -X POST http://localhost:8080/wallets/<id>/topup \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $SERVICE_TOKEN" \
  -d '{"amount":500}'

# 6. Deduct ₹100 (SERVICE or ADMIN — Order Service uses SERVICE token)
curl -s -X POST http://localhost:8080/wallets/<id>/deduct \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $SERVICE_TOKEN" \
  -H "Idempotency-Key: ORDER-001" \
  -d '{"referenceId":"ORDER-001"}'

# 7. Check balance (CUSTOMER can only read their own wallet)
curl -s http://localhost:8080/wallets/<id>/balance \
  -H "Authorization: Bearer $CUSTOMER_TOKEN"

# 8. Run the Order Service stub
pip install requests
python order-stub/order_stub.py <wallet-id> --orders 3
python order-stub/order_stub.py <wallet-id> --orders 5 --concurrent
python order-stub/order_stub.py <wallet-id> --idempotency-test
```

---

## Authentication

All endpoints (except `/auth/token` and `/actuator/health`) require a JWT bearer token.

```
Authorization: Bearer <token>
```

**Get a token:**
```bash
curl -X POST /auth/token \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-1","role":"CUSTOMER"}'   # role: CUSTOMER | SERVICE | ADMIN
```

| Role | Who uses it | What they can do |
|------|-------------|-----------------|
| `ADMIN` | Ops / internal tools | Everything — create wallets, topup, deduct, read any wallet |
| `SERVICE` | Order Service (server-to-server) | Topup + deduct on any wallet |
| `CUSTOMER` | End user | Read **own wallet only** (balance + transactions) |

A `CUSTOMER` token with `customerId=CUST-1` can only read wallets where `customerId=CUST-1`. Attempting to read another customer's wallet returns **403**.

---

## API Reference

| Method | Path | Required Role | Extra Header | Purpose |
|--------|------|--------------|--------------|---------|
| POST | `/auth/token` | — | — | Get JWT token |
| POST | `/wallets` | `ADMIN` | — | Create wallet |
| POST | `/wallets/:id/topup` | `SERVICE` or `ADMIN` | — | Add funds |
| POST | `/wallets/:id/deduct` | `SERVICE` or `ADMIN` | `Idempotency-Key: <order_id>` | Deduct ₹100 |
| GET | `/wallets/:id/balance` | Any (CUSTOMER = own only) | — | Current balance |
| GET | `/wallets/:id/transactions` | Any (CUSTOMER = own only) | — | Full ledger |

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

### 3. Concurrency — three-layer safety net

Every deduction passes through three independent guards, each defending against a different failure mode:

**Layer 1 — Redisson distributed lock** (`lock:wallet:<id>`, 10 s max hold)

Before any DB access, the service acquires a Redis-backed distributed lock scoped to the wallet ID. This serialises all deductions for a given wallet across every service instance — a second request for the same wallet blocks at this gate until the first completes. Because the lock lives in Redis (not in-process), it works correctly whether you have 1 pod or 100.

- Lock key: `lock:wallet:<walletId>`
- Max hold time: 10 seconds (auto-released if the JVM crashes mid-transaction)
- Implemented via Redisson `RLock` — uses Redis `SET NX PX` under the hood

**Layer 2 — PostgreSQL `SELECT FOR UPDATE`** inside the JPA transaction

Even after acquiring the distributed lock, the balance row is fetched with a pessimistic write lock. This is the safety net for two scenarios the app lock cannot prevent:
1. The Redisson lock expires just before the DB write (slow GC pause, network delay)
2. A script or admin tool bypasses the service and hits PostgreSQL directly

`SELECT FOR UPDATE` ensures only one transaction can read-then-write a wallet's balance row at a time at the database level.

**Layer 3 — PostgreSQL `CHECK (balance >= 0)` constraint**

The hard floor. Even if both locks are somehow bypassed, the database itself will reject any update that would push the balance below zero. This is the last line of defence and cannot be circumvented by application bugs.

```
Request → [Redisson lock] → [SELECT FOR UPDATE] → [CHECK constraint] → commit
```

This three-layer approach means the system is correct whether you have one pod or one hundred, and remains correct even under partial infrastructure failure.

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

### 7. Distributed rate limiting

Every write endpoint is rate-limited per wallet using Redisson's token bucket (`RRateLimiter`), which is distributed — limits are enforced correctly across all service instances via Redis.

| Endpoint | Limit | Reason |
|---|---|---|
| `POST /deduct` | 20 req / min / wallet | Prevents Order Service runaway bugs |
| `POST /topup` | 10 req / min / wallet | Prevents abuse |
| `GET /balance`, `GET /transactions` | 60 req / min / wallet | Protects read path |
| `POST /wallets` | 10 req / min / IP | Prevents mass wallet creation |

Returns **HTTP 429** when exceeded. Limits are configurable in `application.yml` with no code change.

### 8. Wallet status lifecycle

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