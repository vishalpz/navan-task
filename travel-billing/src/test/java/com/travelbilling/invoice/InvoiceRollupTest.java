package com.travelbilling.invoice;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/** Brief §3 rollups: tax_rollup groups by (name, rate); fee_rollup holds only fees from inside transactions. */
class InvoiceRollupTest extends IntegrationTest {

    @Test
    void tripRollupsMatchTheFixture() {
        Map<String, String> trip = createTrip();
        String invoiceId = postInvoice(Map.of("transaction_ids", List.copyOf(trip.values())))
            .statusCode(201).extract().path("id");

        given().when().get("/invoices/" + invoiceId)
            .then().statusCode(200)
            .body("tax_rollup", hasSize(2))
            .body("tax_rollup[0].name", equalTo("Airport tax"))
            .body("tax_rollup[0]", not(hasKey("rate")))
            .body("tax_rollup[0].amount", money("15"))
            .body("tax_rollup[1].name", equalTo("VAT"))
            .body("tax_rollup[1].rate", money("0.07"))
            .body("tax_rollup[1].amount", money("21.70"))

            // Only YQ and the booking fee. Not breakfast (hotel metadata), not the Navan fees (their own lines).
            .body("fee_rollup", hasSize(2))
            .body("fee_rollup[0].name", equalTo("YQ"))
            .body("fee_rollup[0].amount", money("25"))
            .body("fee_rollup[1].name", equalTo("Booking fee"))
            .body("fee_rollup[1].amount", money("4"));
    }

    @Test
    void sameTaxNameAndRateIsSummedButDifferentRatesStaySeparate() {
        String flight1 = createTransaction(fixture("flight"));
        ObjectNode secondFlight = fixture("flight");
        secondFlight.put("external_id", "flight-LHR-TXL-2026-03-14");
        String flight2 = createTransaction(secondFlight);

        String hotel1 = createTransaction(fixture("hotel"));   // VAT 0.07
        ObjectNode otherHotel = fixture("hotel");
        otherHotel.put("external_id", "hotel-PAR-2026-03-13");
        ((ObjectNode) otherHotel.get("tax_lines").get(0)).put("rate", new java.math.BigDecimal("0.10"));
        String hotel2 = createTransaction(otherHotel);          // VAT 0.10, a different group

        String invoiceId = postInvoice(Map.of("transaction_ids", List.of(flight1, flight2, hotel1, hotel2)))
            .statusCode(201).extract().path("id");

        given().when().get("/invoices/" + invoiceId)
            .then().statusCode(200)
            .body("tax_rollup", hasSize(3))
            .body("tax_rollup[0].name", equalTo("Airport tax"))
            .body("tax_rollup[0].amount", money("30"))         // 15 + 15
            .body("tax_rollup[1].rate", money("0.07"))
            .body("tax_rollup[2].rate", money("0.1"))
            .body("fee_rollup", hasSize(1))
            .body("fee_rollup[0].name", equalTo("YQ"))
            .body("fee_rollup[0].amount", money("50"));         // 25 + 25
    }
}
