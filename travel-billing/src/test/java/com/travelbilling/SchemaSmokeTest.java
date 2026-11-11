package com.travelbilling;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Phase 1 checks: the app boots, Flyway built the schema, and the
 * database-level rules we rely on later are really there.
 */
class SchemaSmokeTest extends IntegrationTest {

    @Test
    void healthReportsDatabaseUp() {
        given()
            .when().get("/health")
            .then().statusCode(200)
            .body("status", equalTo("ok"))
            .body("database", equalTo("up"));
    }

    @Test
    void flywayCreatedAllTables() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

        assertThat(tables).contains(
            "transactions", "transaction_tax_lines", "transaction_fee_lines",
            "flight_details", "flight_coupons", "hotel_details", "hotel_extras",
            "rail_legs", "navan_fee_details",
            "invoices", "invoice_lines", "invoice_tax_rollup", "invoice_fee_rollup");
    }

    @Test
    void externalIdIsUniqueInTheDatabase() {
        insertTransaction("dup-1");

        assertThatThrownBy(() -> insertTransaction("dup-1"))
            .isInstanceOf(DuplicateKeyException.class);

        Integer rows = jdbc.queryForObject(
            "SELECT count(*) FROM transactions WHERE external_id = 'dup-1'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void unknownTypeAndUnknownFeeKindAreRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO transactions (type, external_id, currency, occurred_at, total)
                VALUES ('cruise', 'x-1', 'EUR', now(), 1.00)"""))
            .isInstanceOf(DataIntegrityViolationException.class);

        long id = insertTransaction("fee-1");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO navan_fee_details (transaction_id, fee_kind) VALUES (?, 'mystery_fee')", id))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void billedTransactionCannotBeDeletedAtDatabaseLevel() {
        long txnId = insertTransaction("billed-1");
        Long invoiceId = jdbc.queryForObject(
            "INSERT INTO invoices (currency, grand_total) VALUES ('EUR', 10.00) RETURNING id", Long.class);
        jdbc.update("""
                INSERT INTO invoice_lines (invoice_id, transaction_id, position, type, external_id,
                                           currency, occurred_at, total, tax_lines, fee_lines, metadata)
                VALUES (?, ?, 0, 'navan_fee', 'billed-1', 'EUR', now(), 10.00, '[]', '[]', '{}')""",
            invoiceId, txnId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM transactions WHERE id = ?", txnId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void moneyIsStoredExactly() {
        jdbc.update("""
                INSERT INTO transactions (type, external_id, currency, occurred_at, total)
                VALUES ('navan_fee', 'money-1', 'EUR', now(), 0.1), ('navan_fee', 'money-2', 'EUR', now(), 0.2)""");

        String sum = jdbc.queryForObject(
            "SELECT sum(total)::text FROM transactions WHERE external_id IN ('money-1','money-2')", String.class);
        assertThat(sum).isEqualTo("0.3000");   // not 0.30000000000000004
    }

    private long insertTransaction(String externalId) {
        return jdbc.queryForObject("""
                INSERT INTO transactions (type, external_id, currency, occurred_at, total)
                VALUES ('navan_fee', ?, 'EUR', now(), 10.00) RETURNING id""",
            Long.class, externalId);
    }
}
