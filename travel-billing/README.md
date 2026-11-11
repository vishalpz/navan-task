# Travel billing API

Flight, hotel, rail and Navan-fee transactions, plus invoices built as frozen snapshots of those transactions.

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · plain JDBC (`NamedParameterJdbcTemplate`, no JPA).
Design notes: [ARCHITECTURE.md](ARCHITECTURE.md).

## Requirements

- Java 21
- Docker (Docker Desktop on Mac/Windows), running

No API keys or other secrets are needed.

## Run the API (one command)

```bash
./mvnw spring-boot:run
```

This starts Postgres from `docker-compose.yml`, applies the Flyway migrations and serves on `http://localhost:8080`.

```bash
curl -s localhost:8080/health
# {"status":"ok","database":"up"}
```

Data persists in a Docker volume between runs. To start from an empty database: `docker compose down -v`.

## Run the tests (one command)

```bash
./mvnw test
```

Tests start their own throwaway Postgres container (Testcontainers) and call the real HTTP API on a random port.
On Windows without a POSIX shell, use `mvnw.cmd test` / `mvnw.cmd spring-boot:run`.

| Brief test | Where |
|---|---|
| 1. Create every type, GET returns its metadata | `transaction/CreateAndGetTransactionTest` |
| 2. Duplicate `external_id` → one row, 409 | `transaction/DuplicateTransactionTest` |
| 3. Trip invoice, grand total 685.70 | `invoice/TripInvoiceTest` |
| 4. Snapshot: stored flight changes, invoice still 220.00 | `invoice/InvoiceSnapshotTest` |
| 5. Mixed currency → 409 | `invoice/InvoiceRulesTest#mixedCurrencyIs409` |
| 6. Concurrent duplicate POSTs → one row | `transaction/DuplicateTransactionTest#simultaneousDuplicatesCreateExactlyOneRow` |
| 7. PATCH invoiced transaction → 409, invoice unchanged | `transaction/PatchTransactionTest#patchOfAnInvoicedTransactionIs409AndChangesNothing` |
| 8. DELETE invoiced transaction → 409, row still exists | `transaction/DeleteTransactionTest` |
| 9. DELETE invoice, transactions still GET | `invoice/PatchAndDeleteInvoiceTest#deleteRemovesTheInvoiceButKeepsTheTransactions` |
| 10. List: default 50, `limit=1000` → 400 | `transaction/ListTransactionsTest` |

Also covered: atomic create (rollback when a child insert fails), a DELETE waiting on a row lock held by an
in-progress invoice, rollup grouping, invoice PATCH rebuild/rollback, validation errors, exact decimal round-trips.

## API

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/transactions` | 201 + stored transaction | 400 invalid · **409 duplicate `external_id`** (body has `existing_id`) |
| `GET` | `/transactions/{id}` | 200 | 404 |
| `GET` | `/transactions?limit=&cursor=` | 200 page | 400 if `limit` not in 1..100 |
| `PATCH` | `/transactions/{id}` | 200 | 400 · 404 · **409 if on any invoice** |
| `DELETE` | `/transactions/{id}` | 204 | 404 · **409 if on any invoice** |
| `POST` | `/invoices` | 201 + invoice | 400 · 404 unknown id · 409 mixed currency · 409 same id twice |
| `GET` | `/invoices/{id}` | 200 | 404 |
| `GET` | `/invoices?limit=&cursor=` | 200 page | 400 if `limit` not in 1..100 |
| `PATCH` | `/invoices/{id}` | 200, snapshot rebuilt | 404 · 409 (old invoice left unchanged) |
| `DELETE` | `/invoices/{id}` | 204 (transactions are kept) | 404 |

Conventions:

- JSON is snake_case. Ids are strings: `txn_<n>`, `inv_<n>`.
- **Duplicates:** a second POST with an existing `external_id` returns **409** with `existing_id`. Only `external_id`
  is compared; the rest of the body is ignored.
- **Money:** exact decimals, stored as `NUMERIC(19,4)` (up to 4 decimal places; a 5th → 400, never silently rounded).
  Responses drop padding zeros only: stored `220.0000` is returned as `220`, `331.7000` as `331.7`.
- **PATCH transaction:** send only the fields to change (`occurred_at`, `total`, `tax_lines`, `fee_lines`, `metadata`).
  Lists and `metadata` replace the old value whole. `type`, `external_id` and `currency` cannot change (400).
- **Invoice body:** `{"transaction_id": "txn_1"}` or `{"transaction_ids": ["txn_1", ...]}` (max 500). The same id
  twice → 409 (never double-counted). PATCH takes the same body.
- **Lists:** `limit` defaults to 50, max 100 (above → 400, not silently capped). Pass `next_cursor` back as
  `?cursor=` for the next page; the last page has no `next_cursor`.
- **Errors:** `{"error": "<code>", "message": "...", "details": [...]}`, e.g. `duplicate_external_id`,
  `transaction_invoiced`, `mixed_currency`, `validation_failed`, `not_found`.

## Volume: indexes, limits, constraints

All of these are in the Flyway migrations (`src/main/resources/db/migration`), not only described here:

- **`UNIQUE (external_id)`** on `transactions`: duplicate identity is enforced by the database, including for
  concurrent POSTs (`INSERT … ON CONFLICT (external_id) DO NOTHING`).
- **`ix_invoice_lines_transaction_id`**: makes "is this transaction on an invoice?" cheap. It runs on every
  PATCH/DELETE.
- **Primary keys** on `transactions.id` / `invoices.id` serve `GET /{id}` and the list pages. Each child table is
  keyed `(transaction_id, position)` or `(invoice_id, …)`, so loading a record's children is an index lookup.
- **Lists always use `LIMIT`** (default 50, max 100, above → 400) with a keyset cursor (`WHERE id > :cursor`),
  never `OFFSET` or in-memory paging. A page's children are loaded in one `IN (...)` query per table (no N+1).
- **Row locks** (`SELECT … FOR UPDATE`, ascending id order) stop concurrent PATCH/DELETE/invoicing from tearing a
  record's tax or fee lines.

Details and "what fails first": [ARCHITECTURE.md → High volume](ARCHITECTURE.md#high-volume).

## Example requests

The ids below are what you get when you run the steps in order on an **empty** database (`docker compose down -v`
first). Responses are trimmed with `…`.

### Create one of each type

```bash
# flight → txn_1
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "flight", "external_id": "flight-TXL-LHR-2026-03-11", "currency": "EUR",
  "occurred_at": "2026-03-11T06:40:00Z", "total": 220.00,
  "tax_lines": [{ "name": "Airport tax", "amount": 15.00 }],
  "fee_lines": [{ "name": "YQ", "amount": 25.00 }],
  "metadata": {
    "origin": "TXL", "destination": "LHR", "fare": 180.00,
    "coupons": [{ "carrier": "BA", "flight_number": "BA982", "from": "TXL", "to": "LHR",
                  "departure": "2026-03-11T07:00:00Z", "arrival": "2026-03-11T08:20:00Z" }]
  }
}'
# 201 {"id":"txn_1","type":"flight","external_id":"flight-TXL-LHR-2026-03-11","currency":"EUR",
#      "occurred_at":"2026-03-11T06:40:00Z","total":220,"tax_lines":[{"name":"Airport tax","amount":15}],
#      "fee_lines":[{"name":"YQ","amount":25}],"metadata":{"origin":"TXL","destination":"LHR","fare":180,
#      "coupons":[{"carrier":"BA","flight_number":"BA982","from":"TXL","to":"LHR",…}]},"created_at":…}

# hotel → txn_2
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "hotel", "external_id": "hotel-LON-2026-03-11", "currency": "EUR",
  "occurred_at": "2026-03-11T15:00:00Z", "total": 331.70,
  "tax_lines": [{ "name": "VAT", "rate": 0.07, "amount": 21.70 }],
  "fee_lines": [],
  "metadata": {
    "name": "Hotel Mitte London",
    "address": { "street": "12 Kingsway", "city": "London", "country": "GB" },
    "check_in": "2026-03-11", "check_out": "2026-03-13", "nights": 2,
    "room_rate_per_night": 140.00, "extras": [{ "name": "Breakfast", "amount": 30.00 }]
  }
}'

# rail (two legs: a change of train at Lille) → txn_3
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "rail", "external_id": "rail-STP-PAR-2026-03-13", "currency": "EUR",
  "occurred_at": "2026-03-13T09:00:00Z", "total": 94.00,
  "tax_lines": [], "fee_lines": [{ "name": "Booking fee", "amount": 4.00 }],
  "metadata": { "legs": [
    { "origin": "London St Pancras", "destination": "Lille Europe", "departure": "2026-03-13T09:00:00Z",
      "arrival": "2026-03-13T10:30:00Z", "train_number": "EST9108", "class": "standard" },
    { "origin": "Lille Europe", "destination": "Paris Nord", "departure": "2026-03-13T11:00:00Z",
      "arrival": "2026-03-13T12:05:00Z", "train_number": "EST9122", "class": "standard" }
  ] }
}'

# Navan trip fee → txn_4
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "navan_fee", "external_id": "navan-trip-fee-2026-03-11", "currency": "EUR",
  "occurred_at": "2026-03-11T06:40:00Z", "total": 25.00, "tax_lines": [], "fee_lines": [],
  "metadata": { "fee_kind": "trip_fee", "description": "Navan trip fee",
                "related_external_id": "flight-TXL-LHR-2026-03-11" }
}'

# Navan agent call fee → txn_5
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "navan_fee", "external_id": "navan-agent-call-2026-03-11", "currency": "EUR",
  "occurred_at": "2026-03-11T18:00:00Z", "total": 15.00, "tax_lines": [], "fee_lines": [],
  "metadata": { "fee_kind": "agent_call_fee", "description": "Agent fee for support call",
                "related_external_id": "hotel-LON-2026-03-11" }
}'
```

Validation example: an unknown `type` (or `fee_kind`) is rejected.

```bash
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' \
  -d '{"type":"cruise","external_id":"x","currency":"EUR","occurred_at":"2026-03-11T06:40:00Z","total":1,"tax_lines":[],"fee_lines":[],"metadata":{}}'
# 400 {"error":"malformed_request","message":"invalid value for 'type'"}
```

### Duplicate create: same `external_id` twice, one row

The flight was already POSTed above (`txn_1`). This POSTs the fixture's `duplicate_flight`: the same `external_id`
again, this time with `coupons: []`.

```bash
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "flight", "external_id": "flight-TXL-LHR-2026-03-11", "currency": "EUR",
  "occurred_at": "2026-03-11T06:40:00Z", "total": 220.00,
  "tax_lines": [{ "name": "Airport tax", "amount": 15.00 }],
  "fee_lines": [{ "name": "YQ", "amount": 25.00 }],
  "metadata": { "origin": "TXL", "destination": "LHR", "fare": 180.00, "coupons": [] }
}'
# 409 {"error":"duplicate_external_id","message":"a transaction with this external_id already exists","existing_id":"txn_1"}

# Proof straight from the database: still exactly one row.
docker compose exec postgres psql -U travelbilling -d travelbilling -tc \
  "SELECT count(*) FROM transactions WHERE external_id = 'flight-TXL-LHR-2026-03-11'"
# 1
```

It's a 409, not a 400 for the empty coupon list. The identity check runs before the type-specific checks.
The rejected POST still used up one id from the sequence, which is normal for Postgres. Ids can have gaps, so the
next transaction will be `txn_7`, not `txn_6`.

### Get and list transactions

```bash
curl -s localhost:8080/transactions/txn_1
# 200 {"id":"txn_1","type":"flight",…,"metadata":{…,"coupons":[{"flight_number":"BA982",…}]}}

curl -s 'localhost:8080/transactions?limit=2'
# 200 {"data":[{"id":"txn_1",…},{"id":"txn_2",…}],"limit":2,"next_cursor":"txn_2"}

curl -s 'localhost:8080/transactions?limit=2&cursor=txn_2'
# 200 {"data":[{"id":"txn_3",…},{"id":"txn_4",…}],"limit":2,"next_cursor":"txn_4"}

curl -s 'localhost:8080/transactions?limit=1000'
# 400 {"error":"validation_failed","message":"request is invalid","details":["limit: must be between 1 and 100"]}
```

### PATCH a transaction (not on any invoice yet)

```bash
# One more fee → txn_7
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "navan_fee", "external_id": "navan-agent-call-2026-03-14", "currency": "EUR",
  "occurred_at": "2026-03-14T10:00:00Z", "total": 15.00, "tax_lines": [], "fee_lines": [],
  "metadata": { "fee_kind": "agent_call_fee", "description": "Second support call" }
}'

curl -s -X PATCH localhost:8080/transactions/txn_7 -H 'Content-Type: application/json' \
  -d '{ "total": 17.85, "tax_lines": [{ "name": "VAT", "rate": 0.19, "amount": 2.85 }] }'
# 200 {"id":"txn_7",…,"total":17.85,"tax_lines":[{"name":"VAT","rate":0.19,"amount":2.85}],…}
```

### Create invoices

```bash
# The whole trip → inv_1
curl -s -X POST localhost:8080/invoices -H 'Content-Type: application/json' \
  -d '{ "transaction_ids": ["txn_1", "txn_2", "txn_3", "txn_4", "txn_5"] }'
# 201 {"id":"inv_1","currency":"EUR",
#      "totals":{"flight":220,"hotel":331.7,"rail":94,"trip_fee":25,"agent_call_fee":15,"grand_total":685.7},
#      "lines":[{"transaction_id":"txn_1","type":"flight","total":220,"tax_lines":[…],"fee_lines":[…],
#                "metadata":{…"coupons":[{…"flight_number":"BA982"}]…}}, …],
#      "tax_rollup":[{"name":"Airport tax","amount":15},{"name":"VAT","rate":0.07,"amount":21.7}],
#      "fee_rollup":[{"name":"YQ","amount":25},{"name":"Booking fee","amount":4}], …}

# A single transaction → inv_2
curl -s -X POST localhost:8080/invoices -H 'Content-Type: application/json' -d '{ "transaction_id": "txn_7" }'
# 201 {"id":"inv_2","currency":"EUR","totals":{"agent_call_fee":17.85,"grand_total":17.85},…}
```

### Get and list invoices

```bash
curl -s localhost:8080/invoices/inv_1
# 200 {"id":"inv_1",…,"totals":{…,"grand_total":685.7},…}

curl -s 'localhost:8080/invoices?limit=1'
# 200 {"data":[{"id":"inv_1",…}],"limit":1,"next_cursor":"inv_1"}
```

### A transaction on an invoice is frozen

```bash
curl -s -X PATCH localhost:8080/transactions/txn_1 -H 'Content-Type: application/json' -d '{ "total": 999.99 }'
# 409 {"error":"transaction_invoiced","message":"transaction txn_1 is on invoice(s) inv_1 and cannot be changed; delete or PATCH those invoices first"}

curl -s -X DELETE localhost:8080/transactions/txn_1
# 409 {"error":"transaction_invoiced","message":"transaction txn_1 is on invoice(s) inv_1 and cannot be deleted; delete or PATCH those invoices first"}
```

### PATCH an invoice (rebuilds the snapshot from current data)

```bash
curl -s -X PATCH localhost:8080/invoices/inv_1 -H 'Content-Type: application/json' \
  -d '{ "transaction_ids": ["txn_1", "txn_2", "txn_3"] }'
# 200 {"id":"inv_1",…,"totals":{"flight":220,"hotel":331.7,"rail":94,"grand_total":645.7},…}
```

### DELETE an invoice, then its transaction is free again

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE localhost:8080/invoices/inv_2       # 204
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE localhost:8080/transactions/txn_7   # 204
curl -s localhost:8080/transactions/txn_7
# 404 {"error":"not_found","message":"transaction txn_7 not found"}
```

### Mixed currency is refused

```bash
curl -s -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{
  "type": "navan_fee", "external_id": "usd-fee-1", "currency": "USD",
  "occurred_at": "2026-03-11T06:40:00Z", "total": 10.00, "tax_lines": [], "fee_lines": [],
  "metadata": { "fee_kind": "trip_fee" } }'
# 201 {"id":"txn_8",…,"currency":"USD",…}

curl -s -X POST localhost:8080/invoices -H 'Content-Type: application/json' -d '{ "transaction_ids": ["txn_1", "txn_8"] }'
# 409 {"error":"mixed_currency","message":"all transactions on an invoice must share one currency, found EUR and USD"}
```
