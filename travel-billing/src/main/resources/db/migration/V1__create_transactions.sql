-- =====================================================================
-- V1: Transactions (flight, hotel, rail, navan_fee)
--
-- Money: NUMERIC(19,4) everywhere. 4 decimal places of storage covers
-- every ISO currency (JPY 0, EUR 2, BHD 3); the API rounds for display.
-- Rates (e.g. VAT 0.07): NUMERIC(9,6).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Shared header: one row per transaction, whatever its type
-- ---------------------------------------------------------------------
CREATE TABLE transactions (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type         VARCHAR(20)    NOT NULL,
    external_id  VARCHAR(255)   NOT NULL,
    currency     CHAR(3)        NOT NULL,
    occurred_at  TIMESTAMPTZ    NOT NULL,
    total        NUMERIC(19, 4) NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_transactions_type
        CHECK (type IN ('flight', 'hotel', 'rail', 'navan_fee')),

    -- Duplicate identity lives in the database, not in Java.
    -- Also backs the lookup "which id has this external_id?" after a conflict.
    CONSTRAINT uq_transactions_external_id UNIQUE (external_id)
);

-- ---------------------------------------------------------------------
-- Taxes and fees: two separate tables, never mixed
-- ---------------------------------------------------------------------
CREATE TABLE transaction_tax_lines (
    transaction_id BIGINT         NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INT            NOT NULL,
    name           VARCHAR(255)   NOT NULL,
    rate           NUMERIC(9, 6),            -- optional: airport tax has no rate, VAT does
    amount         NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (transaction_id, position)    -- keeps list order; doubles as the FK index
);

CREATE TABLE transaction_fee_lines (
    transaction_id BIGINT         NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INT            NOT NULL,
    name           VARCHAR(255)   NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

-- ---------------------------------------------------------------------
-- flight: header details + one row per coupon (segment)
-- ---------------------------------------------------------------------
CREATE TABLE flight_details (
    transaction_id BIGINT         PRIMARY KEY REFERENCES transactions (id) ON DELETE CASCADE,
    origin         VARCHAR(100)   NOT NULL,
    destination    VARCHAR(100)   NOT NULL,
    fare           NUMERIC(19, 4) NOT NULL
);

CREATE TABLE flight_coupons (
    transaction_id BIGINT       NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INT          NOT NULL,
    flight_number  VARCHAR(20)  NOT NULL,
    carrier        VARCHAR(10),
    from_location  VARCHAR(100) NOT NULL,
    to_location    VARCHAR(100) NOT NULL,
    departure      TIMESTAMPTZ  NOT NULL,
    arrival        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

-- ---------------------------------------------------------------------
-- hotel: property name + address as real columns, extras as child rows
-- ---------------------------------------------------------------------
CREATE TABLE hotel_details (
    transaction_id      BIGINT         PRIMARY KEY REFERENCES transactions (id) ON DELETE CASCADE,
    name                VARCHAR(255)   NOT NULL,
    street              VARCHAR(255)   NOT NULL,
    city                VARCHAR(100)   NOT NULL,
    country             VARCHAR(100)   NOT NULL,
    check_in            DATE           NOT NULL,
    check_out           DATE           NOT NULL,
    nights              INT            NOT NULL,
    room_rate_per_night NUMERIC(19, 4) NOT NULL,

    CONSTRAINT ck_hotel_dates CHECK (check_out > check_in),
    CONSTRAINT ck_hotel_nights CHECK (nights > 0)
);

CREATE TABLE hotel_extras (
    transaction_id BIGINT         NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INT            NOT NULL,
    name           VARCHAR(255)   NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (transaction_id, position)
);

-- ---------------------------------------------------------------------
-- rail: one row per leg (a change of train = two legs)
-- ---------------------------------------------------------------------
CREATE TABLE rail_legs (
    transaction_id BIGINT       NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    position       INT          NOT NULL,
    origin         VARCHAR(255) NOT NULL,
    destination    VARCHAR(255) NOT NULL,
    departure      TIMESTAMPTZ  NOT NULL,
    arrival        TIMESTAMPTZ  NOT NULL,
    train_number   VARCHAR(20),
    travel_class   VARCHAR(50),              -- "class" is reserved in Java; API still says "class"
    PRIMARY KEY (transaction_id, position)
);

-- ---------------------------------------------------------------------
-- navan_fee: Navan's own charges (trip fee, agent call fee)
-- ---------------------------------------------------------------------
CREATE TABLE navan_fee_details (
    transaction_id      BIGINT       PRIMARY KEY REFERENCES transactions (id) ON DELETE CASCADE,
    fee_kind            VARCHAR(30)  NOT NULL,
    description         VARCHAR(500),
    related_external_id VARCHAR(255),

    CONSTRAINT ck_navan_fee_kind CHECK (fee_kind IN ('trip_fee', 'agent_call_fee'))
);
