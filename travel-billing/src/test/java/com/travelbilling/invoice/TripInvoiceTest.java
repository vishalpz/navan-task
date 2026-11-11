package com.travelbilling.invoice;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.travelbilling.IntegrationTest;

/**
 * Brief test #3: the whole fixture trip on one invoice. Totals match expected-invoice.json
 * and every line carries its type-specific metadata.
 */
class TripInvoiceTest extends IntegrationTest {

    @Test
    void tripInvoiceHasExpectedTotalsAndMetadata() {
        Map<String, String> trip = createTrip();
        String invoiceId = postInvoice(Map.of("transaction_ids", List.copyOf(trip.values())))
            .statusCode(201)
            .extract().path("id");

        given().when().get("/invoices/" + invoiceId)
            .then().statusCode(200)
            .body("currency", equalTo("EUR"))

            // totals, straight from expected-invoice.json
            .body("totals.flight", money("220.00"))
            .body("totals.hotel", money("331.70"))
            .body("totals.rail", money("94.00"))
            .body("totals.trip_fee", money("25.00"))
            .body("totals.agent_call_fee", money("15.00"))
            .body("totals.grand_total", money("685.70"))

            // lines in request order, each pointing back at its transaction
            .body("lines", hasSize(5))
            .body("lines.type", equalTo(List.of("flight", "hotel", "rail", "navan_fee", "navan_fee")))
            .body("lines.transaction_id", equalTo(List.copyOf(trip.values())))

            // metadata_checks from expected-invoice.json
            .body("lines[0].metadata.coupons[0].flight_number", equalTo("BA982"))
            .body("lines[1].metadata.name", equalTo("Hotel Mitte London"))
            .body("lines[1].metadata.address.city", equalTo("London"))
            .body("lines[2].metadata.legs", hasSize(2))
            .body("lines[3].fee_kind", equalTo("trip_fee"))
            .body("lines[3].metadata.fee_kind", equalTo("trip_fee"))
            .body("lines[4].fee_kind", equalTo("agent_call_fee"));
    }
}
