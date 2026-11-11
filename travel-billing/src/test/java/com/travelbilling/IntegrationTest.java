package com.travelbilling;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Base class for tests that hit the real HTTP API on a random port,
 * backed by a real Postgres container.
 *
 * Each test starts from empty tables so tests don't depend on each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void setUpBase() {
        RestAssured.port = port;
        // Children cascade from these two; invoices first because invoice_lines RESTRICTs transactions.
        jdbc.execute("TRUNCATE invoices, transactions RESTART IDENTITY CASCADE");
    }
}
