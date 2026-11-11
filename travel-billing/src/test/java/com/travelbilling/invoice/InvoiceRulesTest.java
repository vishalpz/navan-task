package com.travelbilling.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/** The 400 / 404 / 409 rules for POST /invoices (brief test #5 = mixedCurrencyIs409). A rejected request leaves no invoice behind. */
class InvoiceRulesTest extends IntegrationTest {

    @Test
    void unknownTransactionIs404() {
        String flight = createTransaction(fixture("flight"));

        postInvoice(Map.of("transaction_ids", List.of(flight, "txn_999", "garbage")))
            .statusCode(404)
            .body("error", equalTo("not_found"))
            .body("message", containsString("txn_999"))
            .body("message", containsString("garbage"));
        assertThat(countRows("invoices")).isZero();
    }

    @Test
    void sameTransactionTwiceIs409() {
        String flight = createTransaction(fixture("flight"));

        postInvoice(Map.of("transaction_ids", List.of(flight, flight)))
            .statusCode(409)
            .body("error", equalTo("duplicate_transaction_in_request"));
        assertThat(countRows("invoices")).isZero();
    }

    @Test
    void mixedCurrencyIs409() {
        String eur = createTransaction(fixture("flight"));
        ObjectNode usdFee = fixture("trip_fee");
        usdFee.put("currency", "USD");
        String usd = createTransaction(usdFee);

        postInvoice(Map.of("transaction_ids", List.of(eur, usd)))
            .statusCode(409)
            .body("error", equalTo("mixed_currency"));
        assertThat(countRows("invoices")).isZero();
    }

    @Test
    void bodyMustHaveExactlyOneOfTheTwoFields() {
        String flight = createTransaction(fixture("flight"));

        postInvoice("{}").statusCode(400).body("error", equalTo("validation_failed"));
        postInvoice(Map.of("transaction_ids", List.of())).statusCode(400);
        postInvoice(Map.of("transaction_id", flight, "transaction_ids", List.of(flight))).statusCode(400);
        assertThat(countRows("invoices")).isZero();
    }
}
