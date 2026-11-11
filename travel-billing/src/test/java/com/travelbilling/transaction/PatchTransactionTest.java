package com.travelbilling.transaction;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/**
 * PATCH /transactions/{id}. Brief test #7: refused with 409 once the transaction is invoiced.
 * Otherwise it changes only what's sent, and identity fields are fixed.
 */
class PatchTransactionTest extends IntegrationTest {

    @Test
    void patchOfAnInvoicedTransactionIs409AndChangesNothing() {
        Map<String, String> trip = createTrip();
        String flight = trip.get("flight");
        String invoiceId = postInvoice(Map.of("transaction_ids", List.copyOf(trip.values())))
            .statusCode(201).extract().path("id");

        patch("/transactions/" + flight, Map.of("total", 999.99, "tax_lines", List.of()))
            .statusCode(409)
            .body("error", equalTo("transaction_invoiced"))
            .body("message", containsString(invoiceId));

        // Nothing was persisted on the transaction...
        given().get("/transactions/" + flight).then()
            .body("total", money("220"))
            .body("tax_lines", hasSize(1));
        // ...and the invoice snapshot totals are unchanged.
        given().get("/invoices/" + invoiceId).then()
            .body("totals.flight", money("220.00"))
            .body("totals.grand_total", money("685.70"));

        // Once the invoice is gone, the transaction is editable again.
        delete("/invoices/" + invoiceId).statusCode(204);
        patch("/transactions/" + flight, Map.of("total", 999.99)).statusCode(200).body("total", money("999.99"));
    }

    @Test
    void onlyTheSentFieldsChange() {
        String id = createTransaction(fixture("flight"));
        String createdAt = given().get("/transactions/" + id).then().extract().path("created_at");

        patch("/transactions/" + id, Map.of("total", 250))
            .statusCode(200)
            .body("total", money("250"))
            .body("occurred_at", equalTo("2026-03-11T06:40:00Z"))   // untouched
            .body("tax_lines", hasSize(1))                          // untouched
            .body("metadata.coupons", hasSize(1))                   // untouched
            .body("updated_at", not(equalTo(createdAt)));
    }

    @Test
    void taxAndFeeListsAreReplacedNotMerged() {
        String id = createTransaction(fixture("flight"));

        patch("/transactions/" + id, Map.of(
                "tax_lines", List.of(Map.of("name", "VAT", "rate", 0.1, "amount", 10), Map.of("name", "Stamp", "amount", 2)),
                "fee_lines", List.of()))
            .statusCode(200)
            .body("tax_lines.name", equalTo(List.of("VAT", "Stamp")))   // Airport tax is gone
            .body("fee_lines", empty());

        assertThat(countRows("transaction_tax_lines")).isEqualTo(2);
        assertThat(countRows("transaction_fee_lines")).isZero();
    }

    @Test
    void metadataIsReplacedAndValidatedLikeOnCreate() {
        String id = createTransaction(fixture("flight"));

        ObjectNode metadata = (ObjectNode) fixture("flight").get("metadata");
        ArrayNode coupons = (ArrayNode) metadata.get("coupons");
        coupons.add(((ObjectNode) coupons.get(0).deepCopy()).put("flight_number", "BA983"));
        patch("/transactions/" + id, Map.of("metadata", metadata))
            .statusCode(200)
            .body("metadata.coupons.flight_number", equalTo(List.of("BA982", "BA983")));

        // Invalid metadata → 400, and the previous metadata is still there (the PATCH rolled back).
        metadata.putArray("coupons");
        patch("/transactions/" + id, Map.of("metadata", metadata))
            .statusCode(400)
            .body("details", hasItem(startsWith("metadata.coupons")));
        assertThat(countRows("flight_coupons")).isEqualTo(2);
    }

    @Test
    void typeExternalIdAndCurrencyCannotChange() {
        String id = createTransaction(fixture("flight"));

        patch("/transactions/" + id, Map.of("type", "hotel", "external_id", "other", "currency", "USD"))
            .statusCode(400)
            .body("details", hasItems(
                "type: cannot be changed", "external_id: cannot be changed", "currency: cannot be changed"));

        // Sending the CURRENT values is fine, e.g. a client PATCHing back what it got from GET.
        patch("/transactions/" + id, Map.of(
                "type", "flight", "external_id", "flight-TXL-LHR-2026-03-11", "currency", "EUR", "total", 221))
            .statusCode(200)
            .body("total", money("221"));
    }

    @Test
    void unknownTransactionIs404() {
        patch("/transactions/txn_999", Map.of("total", 1)).statusCode(404);
    }
}
