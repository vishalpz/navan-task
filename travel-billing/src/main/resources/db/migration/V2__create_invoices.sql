-- =====================================================================
-- V2: Invoices as frozen snapshots
--
-- An invoice never reads prices through to the transactions tables.
-- Everything it shows is copied into these tables at create/PATCH time.
-- =====================================================================

CREATE TABLE invoices (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    currency    CHAR(3)        NOT NULL,
    grand_total NUMERIC(19, 4) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- One row per transaction on the invoice = the photocopy.
-- Key fields are real columns; the nested lists are frozen JSONB documents
-- (never queried by field, only returned as they were at billing time).
-- ---------------------------------------------------------------------
CREATE TABLE invoice_lines (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_id     BIGINT         NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,

    -- Reference only, never read through for amounts.
    -- RESTRICT = the DB itself refuses to delete a transaction that is billed.
    transaction_id BIGINT         NOT NULL REFERENCES transactions (id) ON DELETE RESTRICT,

    position       INT            NOT NULL,
    type           VARCHAR(20)    NOT NULL,
    fee_kind       VARCHAR(30),               -- set only for navan_fee lines
    external_id    VARCHAR(255)   NOT NULL,
    currency       CHAR(3)        NOT NULL,
    occurred_at    TIMESTAMPTZ    NOT NULL,
    total          NUMERIC(19, 4) NOT NULL,
    tax_lines      JSONB          NOT NULL,
    fee_lines      JSONB          NOT NULL,
    metadata       JSONB          NOT NULL,

    -- Same transaction can't appear twice on one invoice (no double counting),
    -- and this index serves "load all lines of invoice X".
    CONSTRAINT uq_invoice_lines_invoice_transaction UNIQUE (invoice_id, transaction_id),
    CONSTRAINT uq_invoice_lines_invoice_position UNIQUE (invoice_id, position)
);

-- "Is this transaction on any invoice?" — checked on every PATCH/DELETE of a transaction.
CREATE INDEX ix_invoice_lines_transaction_id ON invoice_lines (transaction_id);

-- ---------------------------------------------------------------------
-- Rollups: computed once from the snapshot lines and stored with the invoice
-- ---------------------------------------------------------------------
CREATE TABLE invoice_tax_rollup (
    invoice_id BIGINT         NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    position   INT            NOT NULL,
    name       VARCHAR(255)   NOT NULL,
    rate       NUMERIC(9, 6),
    amount     NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (invoice_id, position)
);

CREATE TABLE invoice_fee_rollup (
    invoice_id BIGINT         NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    position   INT            NOT NULL,
    name       VARCHAR(255)   NOT NULL,
    amount     NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (invoice_id, position)
);
