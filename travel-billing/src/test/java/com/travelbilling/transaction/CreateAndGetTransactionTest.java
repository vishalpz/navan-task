package com.travelbilling.transaction;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import com.travelbilling.IntegrationTest;

/**
 * Test #1: every fixture type can be created with POST and read back with GET,
 * with all of its type-specific metadata intact.
 */
class CreateAndGetTransactionTest extends IntegrationTest {

    @Test
    void flightIsStoredWithCouponsAndReturnedByGet() {
        String id = postTransaction(fixture("flight"))
            .statusCode(201)
            .header("Location", "/transactions/txn_1")
            .body("id", matchesPattern("txn_\\d+"))
            .extract().path("id");

        given().when().get("/transactions/" + id)
            .then().statusCode(200)
            .body("id", equalTo(id))
            .body("type", equalTo("flight"))
            .body("external_id", equalTo("flight-TXL-LHR-2026-03-11"))
            .body("currency", equalTo("EUR"))
            .body("occurred_at", equalTo("2026-03-11T06:40:00Z"))
            .body("total", money("220.00"))
            .body("tax_lines", hasSize(1))
            .body("tax_lines[0].name", equalTo("Airport tax"))
            .body("tax_lines[0].amount", money("15"))
            .body("tax_lines[0]", not(hasKey("rate")))           // airport tax has no rate
            .body("fee_lines", hasSize(1))
            .body("fee_lines[0].name", equalTo("YQ"))
            .body("fee_lines[0].amount", money("25"))
            .body("metadata.origin", equalTo("TXL"))
            .body("metadata.destination", equalTo("LHR"))
            .body("metadata.fare", money("180"))
            .body("metadata.coupons", hasSize(1))
            .body("metadata.coupons[0].carrier", equalTo("BA"))
            .body("metadata.coupons[0].flight_number", equalTo("BA982"))
            .body("metadata.coupons[0].from", equalTo("TXL"))
            .body("metadata.coupons[0].to", equalTo("LHR"))
            .body("metadata.coupons[0].departure", equalTo("2026-03-11T07:00:00Z"))
            .body("metadata.coupons[0].arrival", equalTo("2026-03-11T08:20:00Z"));
    }

    @Test
    void hotelIsStoredWithNameAddressAndExtras() {
        String id = createTransaction(fixture("hotel"));

        given().when().get("/transactions/" + id)
            .then().statusCode(200)
            .body("type", equalTo("hotel"))
            .body("total", money("331.70"))
            .body("tax_lines[0].name", equalTo("VAT"))
            .body("tax_lines[0].rate", money("0.07"))
            .body("tax_lines[0].amount", money("21.70"))
            .body("fee_lines", empty())
            .body("metadata.name", equalTo("Hotel Mitte London"))
            .body("metadata.address.street", equalTo("12 Kingsway"))
            .body("metadata.address.city", equalTo("London"))
            .body("metadata.address.country", equalTo("GB"))
            .body("metadata.check_in", equalTo("2026-03-11"))
            .body("metadata.check_out", equalTo("2026-03-13"))
            .body("metadata.nights", equalTo(2))
            .body("metadata.room_rate_per_night", money("140"))
            .body("metadata.extras", hasSize(1))
            .body("metadata.extras[0].name", equalTo("Breakfast"))
            .body("metadata.extras[0].amount", money("30"));
    }

    @Test
    void railIsStoredWithLegsInOrder() {
        String id = createTransaction(fixture("rail"));

        given().when().get("/transactions/" + id)
            .then().statusCode(200)
            .body("type", equalTo("rail"))
            .body("total", money("94"))
            .body("tax_lines", empty())
            .body("fee_lines[0].name", equalTo("Booking fee"))
            .body("fee_lines[0].amount", money("4"))
            .body("metadata.legs", hasSize(2))
            .body("metadata.legs[0].origin", equalTo("London St Pancras"))
            .body("metadata.legs[0].destination", equalTo("Lille Europe"))
            .body("metadata.legs[0].train_number", equalTo("EST9108"))
            .body("metadata.legs[0].class", equalTo("standard"))
            .body("metadata.legs[1].origin", equalTo("Lille Europe"))
            .body("metadata.legs[1].destination", equalTo("Paris Nord"))
            .body("metadata.legs[1].departure", equalTo("2026-03-13T11:00:00Z"));
    }

    @Test
    void bothNavanFeeKindsAreStored() {
        String tripFee = createTransaction(fixture("trip_fee"));
        String agentFee = createTransaction(fixture("agent_call_fee"));

        given().when().get("/transactions/" + tripFee)
            .then().statusCode(200)
            .body("type", equalTo("navan_fee"))
            .body("total", money("25"))
            .body("metadata.fee_kind", equalTo("trip_fee"))
            .body("metadata.description", equalTo("Navan trip fee"))
            .body("metadata.related_external_id", equalTo("flight-TXL-LHR-2026-03-11"));

        given().when().get("/transactions/" + agentFee)
            .then().statusCode(200)
            .body("total", money("15"))
            .body("metadata.fee_kind", equalTo("agent_call_fee"))
            .body("metadata.related_external_id", equalTo("hotel-LON-2026-03-11"));
    }

    @Test
    void unknownOrMalformedIdIs404() {
        given().when().get("/transactions/txn_999")
            .then().statusCode(404)
            .body("error", equalTo("not_found"));

        given().when().get("/transactions/not-an-id")
            .then().statusCode(404)
            .body("error", equalTo("not_found"));
    }
}
