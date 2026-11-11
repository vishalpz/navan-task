package com.travelbilling.transaction;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/** Bad input gets a 400 that says what's wrong, and money survives the round trip exactly. */
class TransactionValidationTest extends IntegrationTest {

    @Test
    void unknownTypeIsRejected() {
        ObjectNode body = fixture("trip_fee");
        body.put("type", "cruise");

        postTransaction(body)
            .statusCode(400)
            .body("error", equalTo("malformed_request"))
            .body("message", containsString("type"));
    }

    @Test
    void missingEnvelopeFieldsAreAllListed() {
        ObjectNode body = fixture("trip_fee");
        body.remove("external_id");
        body.remove("currency");
        body.remove("tax_lines");   // required (an empty list is fine, a missing one isn't)

        postTransaction(body)
            .statusCode(400)
            .body("error", equalTo("validation_failed"))
            .body("details", hasItems(startsWith("currency:"), startsWith("external_id:"), startsWith("tax_lines:")));
    }

    @Test
    void moreThanFourDecimalsIsRejectedNotSilentlyRounded() {
        ObjectNode body = fixture("trip_fee");
        body.put("total", new java.math.BigDecimal("25.00001"));

        postTransaction(body)
            .statusCode(400)
            .body("details", hasItem(startsWith("total:")));
        assertThat(countRows("transactions")).isZero();
    }

    @Test
    void hotelCheckOutMustBeAfterCheckIn() {
        ObjectNode hotel = fixture("hotel");
        ((ObjectNode) hotel.get("metadata")).put("check_out", "2026-03-10");

        postTransaction(hotel)
            .statusCode(400)
            .body("details", hasItem(containsString("check_out must be after check_in")));
    }

    @Test
    void unknownNavanFeeKindIsRejected() {
        ObjectNode fee = fixture("trip_fee");
        ((ObjectNode) fee.get("metadata")).put("fee_kind", "mystery_fee");

        postTransaction(fee)
            .statusCode(400)
            .body("details", hasItem(startsWith("metadata.fee_kind:")));
    }

    /** 0.1 + 0.2 is the classic floating-point trap (0.30000000000000004 as a double). */
    @Test
    void moneyRoundTripsExactly() {
        String body = """
            {
              "type": "navan_fee",
              "external_id": "money-exact-1",
              "currency": "EUR",
              "occurred_at": "2026-03-11T06:40:00Z",
              "total": 0.3,
              "tax_lines": [ { "name": "A", "amount": 0.1 }, { "name": "B", "amount": 0.2 } ],
              "fee_lines": [],
              "metadata": { "fee_kind": "trip_fee" }
            }
            """;
        String id = createTransaction(body);

        assertThat(jdbc.queryForObject("SELECT total::text FROM transactions", String.class))
            .isEqualTo("0.3000");
        assertThat(jdbc.queryForObject("SELECT sum(amount)::text FROM transaction_tax_lines", String.class))
            .isEqualTo("0.3000");

        String responseText = given().when().get("/transactions/" + id)
            .then().statusCode(200)
            .extract().asString();
        assertThat(responseText).contains("\"total\":0.3,");
    }
}
