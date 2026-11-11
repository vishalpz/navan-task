# Architecture

## Layers

```
api/          HTTP only: controllers, request/response shapes, GlobalExceptionHandler (status codes)
transaction/  TransactionService (rules + @Transactional), MetadataParser, repositories (SQL)
invoice/      InvoiceService, InvoiceAssembler (pure: transactions in → lines, totals, rollups out), InvoiceRepository
common/       public ids (txn_/inv_), paging, JDBC helpers, shared exceptions
```

Plain JDBC (`NamedParameterJdbcTemplate`) instead of JPA, so the SQL that matters (`ON CONFLICT`, `FOR UPDATE`,
`LIMIT`) is in the code where a reviewer can see it. Flyway owns the schema (`db/migration/V1`, `V2`).

## Tables and models

**Transactions** use a shared header table plus child tables per type. Every type-specific field is a real column,
so it's typed, constrained and queryable.

| Table | Holds |
|---|---|
| `transactions` | `id`, `type` (CHECK: 4 values), `external_id` (**UNIQUE**), `currency`, `occurred_at`, `total` |
| `transaction_tax_lines` | government taxes: `name`, `rate` (nullable, since airport tax has none), `amount` |
| `transaction_fee_lines` | carrier/merchant fees on this transaction: `name`, `amount` |
| `flight_details` + `flight_coupons` | origin, destination, fare · one row per coupon: flight_number, carrier, from, to, times |
| `hotel_details` + `hotel_extras` | property name, street/city/country, dates, nights, rate · extras (e.g. breakfast) |
| `rail_legs` | one row per leg (a change of train = two rows), train_number, class |
| `navan_fee_details` | `fee_kind` (CHECK: `trip_fee`, `agent_call_fee`), description, related_external_id |

- **Taxes and fees are two tables.** Rollups group them differently, and a fee is never mistaken for a tax.
- **Navan fees are transactions of their own**, not a column on the flight. They get their own invoice line.
- **Every list child is keyed `(transaction_id, position)`.** This keeps the client's order, and the key doubles as
  the index for loading children.
- In Java, `metadata` is a **sealed interface** with one record per type. The `switch` statements that save and
  load it won't compile if a type is missing.

**Invoices** are snapshot tables and never join back to `transactions` for data.

| Table | Holds |
|---|---|
| `invoices` | `id`, `currency`, `grand_total` |
| `invoice_lines` | one per transaction: copied `type`, `fee_kind`, `external_id`, `currency`, `occurred_at`, `total` as columns, and the copied `tax_lines`, `fee_lines`, `metadata` as **JSONB** |
| `invoice_tax_rollup` / `invoice_fee_rollup` | computed once at build time and stored |

JSONB fits here because a snapshot is a frozen document. It's only ever returned whole, never queried by field.
`invoice_lines.transaction_id` is kept as a reference for "which invoices bill this transaction?". Its foreign
key is `ON DELETE RESTRICT`, so the database itself refuses to delete a billed transaction.
(JSONB sorts object keys, so key order inside `metadata` can differ from the request. The values are identical.)

## Identity: `external_id` and concurrent POSTs

```sql
INSERT INTO transactions (...) VALUES (...)
ON CONFLICT (external_id) DO NOTHING
RETURNING id
```

- **No row returned means the `external_id` already exists.** We look up the existing id and reply
  **409** `{"error":"duplicate_external_id","existing_id":"txn_6"}`. Only `external_id` is compared, never the body.
- **The database's unique index decides**, not a check-then-insert in Java. With two overlapping POSTs, Postgres
  makes the second one wait on the first one's uncommitted row. If the first commits, the second sees a conflict
  (409). If the first rolls back, the second inserts. A test fires 8 identical POSTs at once and gets exactly one 201.
- **`ON CONFLICT` instead of catching the duplicate-key error**, because in Postgres an error aborts the whole
  transaction, and we couldn't then run the lookup.
- **Order of steps.** The id is claimed first and the type-specific rules are checked second, so the fixture's
  `duplicate_flight` (which has `coupons: []`) gets 409, not 400.
- **Atomic create.** Header, tax lines, fee lines and type rows are written in one `@Transactional`. Any failure
  rolls everything back, including the claimed `external_id`.
- A rejected duplicate still uses up a sequence value, so ids can have gaps.

## Invoice snapshot

`POST /invoices` runs in one DB transaction:
1. Reject the same id listed twice (409).
2. Lock the transactions (`FOR UPDATE`, sorted by id); any missing → 404.
3. Load them fully; mixed currency → 409.
4. `InvoiceAssembler` copies each transaction into a line, sums `totals` (per type, with Navan fees split by
   `fee_kind`, plus `grand_total`), and builds `tax_rollup` (grouped by name + rate, where `0.07` and `0.070000`
   count as the same rate) and `fee_rollup` (by name, only the transactions' own `fee_lines`).
5. Store header, lines, rollups.

Totals are the transactions' gross totals added up. We don't recompute a total from its parts: rail has no
fare, and hotel extras are already included in the room total.

**Keeping invoices frozen:**
- PATCH/DELETE of a transaction that is on any invoice → **409**, nothing saved.
- `PATCH /invoices/{id}` is the only way to change an invoice. It deletes the old lines and rollups and builds a
  fresh snapshot from the transactions' current data, in one transaction. On any error the old invoice stays.
- `DELETE /invoices/{id}` removes the snapshot; the transactions stay, and become editable again.

**Choices to know:** the same id listed twice → 409, never de-duplicated. A transaction may appear on more than
one invoice; the schema only forbids it twice on the same invoice. Restricting that would be one extra check.

## Money

- `NUMERIC(19,4)` in Postgres, `BigDecimal` in Java, never `double`. Rates are `NUMERIC(9,6)`.
- Jackson reads JSON decimals straight into `BigDecimal` (`use-big-decimal-for-floats`).
- Comparisons use `compareTo`.
- **Scale is 4 decimal places.** That covers every ISO currency (JPY 0, EUR 2, BHD 3). Input with more decimals
  is rejected (400) rather than silently rounded.
- Responses print the stored value without padding zeros (`220.0000` → `220`). Nothing is rounded.
- There's no currency conversion, which is why mixed currencies on one invoice get a 409.

## Concurrency for updates and deletes

- Every write that depends on a transaction's state first takes `SELECT … FOR UPDATE` on it: PATCH, DELETE and
  invoice create/PATCH. Two overlapping writers on the same row run one after the other, so `tax_lines` can't be
  half-replaced.
- **Locks are always taken in ascending id order**, so overlapping lock sets can't deadlock.
- An invoice PATCH locks its invoice row first, then its transactions. Nothing locks in the opposite order.
- **"Is it invoiced?" is checked after taking the lock.** A DELETE that races an invoice create waits for it,
  then sees the new invoice lines and returns 409. `DeleteTransactionTest` holds the lock itself to prove this.
- Single and list reads use read-only `REPEATABLE READ`, so the several SELECTs that build one object see one
  consistent snapshot.

## High volume

**What fails first under concurrent PATCH + duplicate POST + large lists, and what's in place:**

1. **Duplicate POSTs racing.** An app-level "check, then insert" would let both through. Here the **unique index
   on `external_id`** plus `ON CONFLICT` decides, and a conflict waits only on that one key. No table locks, and
   no Redis/lock service.
2. **Concurrent PATCH/DELETE/invoicing of the same transaction.** Without locks you'd get torn lists, or an
   invoice copying half of an edit. **Row locks** serialise writers per transaction only; unrelated rows never
   wait. The cost is that a very busy single row queues its writers, and every waiting request holds a pool
   connection (Hikari, default 10). Under heavy load the **connection pool runs out first**. Next steps would be
   `lock_timeout` / `statement_timeout` (fail fast with 409/503 instead of queueing) and pool sizing.
3. **Large lists.** These would fail through an unbounded `SELECT *`, `OFFSET` scans that slow down with depth,
   or N+1 child queries.
   - **Keyset paging** (`WHERE id > :cursor ORDER BY id LIMIT :limit+1`) uses the primary-key index, so every page
     costs the same.
   - `limit` defaults to 50; above 100 → 400.
   - A page's children are loaded with **one `IN (...)` query per child table**, at most 10 queries per transaction page and 5 per invoice page,
     whether it holds 1 or 100 items. Nothing is paginated in memory.
4. **Big invoices.** Creating one locks up to 500 transaction rows (capped) for the length of the copy, and its
   JSONB lines make list pages heavy. The next step would be a summary-only invoice list (header + totals) with
   lines fetched per invoice.

**Indexes (in the Flyway migrations):**

| Index | Serves |
|---|---|
| `UNIQUE (external_id)` | duplicate identity + the lookup of the existing id |
| primary keys on `transactions.id`, `invoices.id` | `GET /{id}`, keyset list pages |
| `(transaction_id, position)` primary keys on every child table | batch loading of a page's children, in order |
| `UNIQUE (invoice_id, transaction_id)` on `invoice_lines` | loading an invoice's lines + no transaction twice on one invoice |
| `ix_invoice_lines_transaction_id` | "is this transaction on any invoice?" on every PATCH/DELETE |

Not added on purpose (no query uses them yet): filters by `occurred_at`/`type`. A filtered list would get a
composite index such as `(occurred_at, id)` and a matching keyset cursor.
