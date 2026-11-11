package com.travelbilling.invoice;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/**
 * PATCH /invoices rebuilds a fresh snapshot in one go. DELETE /invoices leaves the transactions alone
 * (brief test #9: deleteRemovesTheInvoiceButKeepsTheTransactions).
 */
class PatchAndDeleteInvoiceTest extends IntegrationTest {

    @Test
    void patchRebuildsTheSnapshotFromCurrentData() {
        String flight = createTransaction(fixture("flight"));
        String hotel = createTransaction(fixture("hotel"));
        String invoiceId = postInvoice(Map.of("transaction_id", hotel)).statusCode(201).extract().path("id");

        // The flight isn't invoiced yet, so it may still be corrected.
        patch("/transactions/" + flight, Map.of("total", 300)).statusCode(200);

        // PATCH the invoice to the new set: everything is copied as it is NOW.
        patch("/invoices/" + invoiceId, Map.of("transaction_ids", List.of(flight, hotel)))
            .statusCode(200)
            .body("id", equalTo(invoiceId))                   // same invoice, new contents
            .body("lines", hasSize(2))
            .body("totals.flight", money("300"))
            .body("totals.hotel", money("331.70"))
            .body("totals.grand_total", money("631.70"))
            .body("tax_rollup.name", equalTo(List.of("Airport tax", "VAT")))
            .body("fee_rollup.name", equalTo(List.of("YQ")));

        assertThat(countRows("invoices")).isEqualTo(1);
        assertThat(countRows("invoice_lines")).isEqualTo(2);    // old line deleted, not left behind
        assertThat(countRows("invoice_tax_rollup")).isEqualTo(2);
    }

    @Test
    void failedPatchLeavesTheOldInvoiceExactlyAsItWas() {
        String flight = createTransaction(fixture("flight"));
        ObjectNode usdFee = fixture("trip_fee");
        usdFee.put("currency", "USD");
        String usd = createTransaction(usdFee);
        String invoiceId = postInvoice(Map.of("transaction_id", flight)).statusCode(201).extract().path("id");

        patch("/invoices/" + invoiceId, Map.of("transaction_ids", List.of(flight, usd)))
            .statusCode(409)
            .body("error", equalTo("mixed_currency"));

        given().get("/invoices/" + invoiceId).then()
            .statusCode(200)
            .body("lines", hasSize(1))
            .body("totals.grand_total", money("220"));
    }

    @Test
    void deleteRemovesTheInvoiceButKeepsTheTransactions() {
        Map<String, String> trip = createTrip();
        String invoiceId = postInvoice(Map.of("transaction_ids", List.copyOf(trip.values())))
            .statusCode(201).extract().path("id");

        delete("/invoices/" + invoiceId).statusCode(204);

        given().get("/invoices/" + invoiceId).then().statusCode(404);
        assertThat(countRows("invoice_lines")).isZero();
        assertThat(countRows("invoice_tax_rollup")).isZero();
        assertThat(countRows("transactions")).isEqualTo(5);
        given().get("/transactions/" + trip.get("flight")).then().statusCode(200).body("total", money("220"));
    }

    @Test
    void unknownInvoiceIs404ForPatchAndDelete() {
        String flight = createTransaction(fixture("flight"));
        patch("/invoices/inv_999", Map.of("transaction_id", flight)).statusCode(404);
        delete("/invoices/inv_999").statusCode(404);
    }
}
