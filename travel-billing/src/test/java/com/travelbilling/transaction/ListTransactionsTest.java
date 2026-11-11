package com.travelbilling.transaction;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/** Brief test #10: GET /transactions is always paged. Default 50, max 100, limit=1000 → 400. */
class ListTransactionsTest extends IntegrationTest {

    @Test
    void defaultPageIs50AndTheCursorLeadsToTheRest() {
        List<String> created = createFees(55);

        given().when().get("/transactions")
            .then().statusCode(200)
            .body("limit", equalTo(50))
            .body("data", hasSize(50))
            .body("data[0].id", equalTo(created.get(0)))
            .body("data[49].id", equalTo(created.get(49)))
            .body("next_cursor", equalTo(created.get(49)));

        given().when().get("/transactions?cursor=" + created.get(49))
            .then().statusCode(200)
            .body("data", hasSize(5))
            .body("data[0].id", equalTo(created.get(50)))
            .body("$", not(hasKey("next_cursor")));            // last page
    }

    @Test
    void limitAbove100IsRejected() {
        given().when().get("/transactions?limit=1000")
            .then().statusCode(400)
            .body("error", equalTo("validation_failed"))
            .body("details", hasItem(startsWith("limit:")));

        given().when().get("/transactions?limit=0").then().statusCode(400);
        given().when().get("/transactions?limit=abc").then().statusCode(400);
        given().when().get("/transactions?limit=100").then().statusCode(200);
    }

    @Test
    void walkingAllPagesVisitsEveryTransactionOnceInOrder() {
        List<String> created = createFees(7);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            var page = given().queryParam("limit", 3)
                .queryParams(cursor == null ? java.util.Map.of() : java.util.Map.of("cursor", cursor))
                .when().get("/transactions")
                .then().statusCode(200)
                .extract().jsonPath();
            seen.addAll(page.getList("data.id", String.class));
            cursor = page.getString("next_cursor");
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(created);   // 3 + 3 + 1, no gaps, no repeats
    }

    @Test
    void listItemsCarryTheirFullTypeSpecificMetadata() {
        createTrip();   // one of each type, so every per-type batch loader runs

        given().when().get("/transactions")
            .then().statusCode(200)
            .body("data", hasSize(5))
            .body("data[0].metadata.coupons[0].flight_number", equalTo("BA982"))
            .body("data[1].metadata.address.city", equalTo("London"))
            .body("data[2].metadata.legs", hasSize(2))
            .body("data[3].metadata.fee_kind", equalTo("trip_fee"))
            .body("data[4].metadata.fee_kind", equalTo("agent_call_fee"));
    }

    @Test
    void badCursorIs400() {
        given().when().get("/transactions?cursor=inv_1").then().statusCode(400);
        given().when().get("/transactions?cursor=garbage").then().statusCode(400);
    }

    private static List<String> createFees(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode fee = fixture("trip_fee");
            fee.put("external_id", "list-fee-" + i);
            ids.add(createTransaction(fee));
        }
        return ids;
    }
}
