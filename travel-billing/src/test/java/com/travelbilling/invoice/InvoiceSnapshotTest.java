package com.travelbilling.invoice;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.travelbilling.IntegrationTest;

/**
 * Brief test #4 (snapshot): after the invoice exists, change the STORED flight total. GET invoice
 * must still show the flight at 220.00.
 *
 * The API refuses to PATCH an invoiced transaction (test #7), so the stored rows are changed
 * directly in SQL. That's the harsher test: if the invoice secretly joined back to the
 * transaction tables, this would expose it.
 */
class InvoiceSnapshotTest extends IntegrationTest {

    @Test
    void invoiceKeepsItsCopyWhenTheStoredTransactionChanges() {
        Map<String, String> trip = createTrip();
        String invoiceId = postInvoice(Map.of("transaction_ids", List.copyOf(trip.values())))
            .statusCode(201).extract().path("id");

        long flightId = dbId(trip.get("flight"));
        jdbc.update("UPDATE transactions SET total = 999.99 WHERE id = ?", flightId);
        jdbc.update("UPDATE transaction_tax_lines SET amount = 77 WHERE transaction_id = ?", flightId);
        jdbc.update("UPDATE flight_coupons SET flight_number = 'XX000' WHERE transaction_id = ?", flightId);

        given().when().get("/invoices/" + invoiceId)
            .then().statusCode(200)
            .body("lines[0].total", money("220.00"))
            .body("lines[0].tax_lines[0].amount", money("15"))
            .body("lines[0].metadata.coupons[0].flight_number", equalTo("BA982"))
            .body("totals.flight", money("220.00"))
            .body("totals.grand_total", money("685.70"))
            .body("tax_rollup[0].amount", money("15"));
    }
}
