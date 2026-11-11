package com.travelbilling;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.restassured.RestAssured;
import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.ValidatableResponse;

/**
 * Base class for tests that hit the real HTTP API on a random port,
 * backed by a real Postgres container.
 *
 * Each test starts from empty tables so tests don't depend on each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    /** Reads JSON decimals as BigDecimal (never double), same as the app does. */
    protected static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static JsonNode seedPayloads;

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void setUpBase() {
        RestAssured.port = port;
        // RestAssured parses response decimals as float by default. That's the wrong tool for money.
        RestAssured.config = RestAssuredConfig.config().jsonConfig(
            JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
        // Children cascade from these two; invoices first because invoice_lines RESTRICTs transactions.
        jdbc.execute("TRUNCATE invoices, transactions RESTART IDENTITY CASCADE");
    }

    // ------------------------------------------------------------------ fixtures

    /** A fresh, editable copy of one payload from fixtures/task-b/seed-payloads.json (e.g. "flight"). */
    protected static ObjectNode fixture(String name) {
        if (seedPayloads == null) {
            try {
                seedPayloads = JSON.readTree(Path.of("fixtures/task-b/seed-payloads.json").toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        JsonNode payload = seedPayloads.get(name);
        if (payload == null) {
            throw new IllegalArgumentException("No fixture named " + name);
        }
        return payload.deepCopy();
    }

    protected static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ HTTP helpers

    protected static ValidatableResponse postTransaction(Object body) {
        String text = body instanceof String s ? s : json(body);
        return given().contentType(ContentType.JSON).body(text)
            .when().post("/transactions")
            .then();
    }

    /** POSTs a payload, asserts 201, returns the new public id ("txn_1"). */
    protected static String createTransaction(Object body) {
        return postTransaction(body).statusCode(201).extract().path("id");
    }

    /** The five fixture transactions of the London/Paris trip, created in order. Returns fixture name → id. */
    protected static Map<String, String> createTrip() {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String name : List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee")) {
            ids.put(name, createTransaction(fixture(name)));
        }
        return ids;
    }

    protected static ValidatableResponse postInvoice(Object body) {
        String text = body instanceof String s ? s : json(body);
        return given().contentType(ContentType.JSON).body(text)
            .when().post("/invoices")
            .then();
    }

    protected static ValidatableResponse patch(String path, Object body) {
        String text = body instanceof String s ? s : json(body);
        return given().contentType(ContentType.JSON).body(text)
            .when().patch(path)
            .then();
    }

    protected static ValidatableResponse delete(String path) {
        return given().when().delete(path).then();
    }

    /** "txn_7" / "inv_7" → 7, for poking the database directly in tests. */
    protected static long dbId(String publicId) {
        return Long.parseLong(publicId.substring(publicId.indexOf('_') + 1));
    }

    // ------------------------------------------------------------------ assertions

    /** Compares money by value with BigDecimal.compareTo: 220 == 220.0 == 220.00, but 0.3 != 0.30000001. */
    protected static Matcher<Object> money(String expected) {
        BigDecimal want = new BigDecimal(expected);
        return new CustomTypeSafeMatcher<>("an amount equal to " + expected) {
            @Override
            protected boolean matchesSafely(Object actual) {
                return new BigDecimal(actual.toString()).compareTo(want) == 0;
            }
        };
    }

    protected int countRows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
