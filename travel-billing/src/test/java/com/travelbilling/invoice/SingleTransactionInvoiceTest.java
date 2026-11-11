package com.travelbilling.invoice;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.travelbilling.IntegrationTest;

/** Brief §3 "support at least a single id": an invoice for { "transaction_id": ... }. */
class SingleTransactionInvoiceTest extends IntegrationTest {

    @Test
    void invoiceForOneTransactionIsCreatedAndReadBack() {
        String flightId = createTransaction(fixture("flight"));

        String invoiceId = postInvoice(Map.of("transaction_id", flightId))
            .statusCode(201)
            .header("Location", "/invoices/inv_1")
            .body("id", equalTo("inv_1"))
            .extract().path("id");

        given().when().get("/invoices/" + invoiceId)
            .then().statusCode(200)
            .body("id", equalTo(invoiceId))
            .body("currency", equalTo("EUR"))
            .body("lines", hasSize(1))
            .body("lines[0].transaction_id", equalTo(flightId))
            .body("lines[0].type", equalTo("flight"))
            .body("lines[0].external_id", equalTo("flight-TXL-LHR-2026-03-11"))
            .body("lines[0].total", money("220"))
            .body("lines[0].tax_lines[0].name", equalTo("Airport tax"))
            .body("lines[0].metadata.coupons[0].flight_number", equalTo("BA982"))
            .body("totals.flight", money("220"))
            .body("totals.grand_total", money("220"))
            .body("tax_rollup", hasSize(1))
            .body("fee_rollup", hasSize(1));
    }

    @Test
    void unknownOrMalformedInvoiceIdIs404() {
        given().when().get("/invoices/inv_999").then().statusCode(404).body("error", equalTo("not_found"));
        given().when().get("/invoices/txn_1").then().statusCode(404);
    }
}
