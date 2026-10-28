package com.practice.cashflow;

import com.practice.cashflow.adapter.out.persistence.JdbcPaymentAcceptanceStore;
import com.practice.cashflow.application.IdempotencyConflictException;
import com.practice.cashflow.application.PaymentAcceptance;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Spring HTTP wiring; database persistence and AWS delivery are deliberately mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"db", "acceptance"})
class Part4BoundaryTest {
    private static final UUID PAYMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-27T10:00:00Z");
    private static final String BODY = """
            {"amountMinor":2500,"currency":"EUR","provider":"STRIPE",
             "paymentMethodToken":"pm_funded","merchantReference":"part4-check"}
            """;

    @MockitoBean(name = "dataSource") HikariDataSource dataSource;
    @MockitoBean JdbcPaymentAcceptanceStore store;
    @Autowired WebApplicationContext context;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired JdbcTransactionManager transactions;
    @Autowired RequestMappingHandlerMapping mappings;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        http = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void acceptanceKeepsItsLocationAndReplayContract() throws Exception {
        when(store.acceptAtomically(any())).thenReturn(
                new PaymentAcceptance(PAYMENT_ID, ACCEPTED_AT, false),
                new PaymentAcceptance(PAYMENT_ID, ACCEPTED_AT, true));
        for (String replayed : new String[]{"false", "true"}) {
            http.perform(post("/v1/payments").header("Idempotency-Key", "part4-check")
                            .contentType("application/json").content(BODY))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", "/v1/payments/" + PAYMENT_ID))
                    .andExpect(header().string("Idempotency-Replayed", replayed))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                    .andExpect(jsonPath("$.acceptedAt").value(ACCEPTED_AT.toString()));
        }
        verify(store, times(2)).acceptAtomically(any());
    }

    @Test
    void conflictStillHasASafeProblemResponse() throws Exception {
        when(store.acceptAtomically(any())).thenThrow(new IdempotencyConflictException());
        http.perform(post("/v1/payments").header("Idempotency-Key", "part4-check")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(header().exists("X-Request-ID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "25.5", "\"2500\""})
    void rejectsInvalidAmountsBeforePersistence(String amount) throws Exception {
        http.perform(post("/v1/payments").header("Idempotency-Key", "part4-check")
                        .contentType("application/json").content(BODY.replace("2500", amount)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test
    void missingKeyIsRejectedBeforePersistence() throws Exception {
        http.perform(post("/v1/payments").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test
    void statusLookupRetainsMerchantScopeAndNotFoundResponse() throws Exception {
        UUID merchant = UUID.fromString("10000000-0000-4000-8000-000000000001");
        when(store.find(merchant, PAYMENT_ID)).thenReturn(Optional.empty());
        http.perform(get("/v1/payments/" + PAYMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(store).find(merchant, PAYMENT_ID);
    }

    @Test
    void storageFailureDoesNotExposeInternalDetails() throws Exception {
        when(store.acceptAtomically(any())).thenThrow(new DataAccessResourceFailureException("private-sql-details"));
        String body = http.perform(post("/v1/payments").header("Idempotency-Key", "part4-check")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_STORAGE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-sql-details"));
        assertTrue(body.contains("same idempotency key"));
    }

    @Test
    void jdbcOperationsAndTransactionsShareTheSameDataSource() {
        assertSame(dataSource, jdbc.getJdbcTemplate().getDataSource());
        assertSame(dataSource, transactions.getDataSource());
    }

    @Test
    void onlyPart4ApplicationEndpointsAndTheirSchemasRemain() throws Exception {
        var paths = mappings.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().getPackageName().startsWith("com.practice.cashflow"))
                .flatMap(e -> e.getKey().getPatternValues().stream())
                .collect(Collectors.toSet());
        assertEquals(Set.of("/v1/payments", "/v1/payments/{paymentId}"), paths);
        try (var input = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
            Map<String, Object> api = new Yaml().load(input);
            assertEquals(Set.of("/payments", "/payments/{paymentId}"), ((Map<?, ?>) api.get("paths")).keySet());
            Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) api.get("components")).get("schemas");
            assertEquals(Set.of("PaymentSubmission", "PaymentAccepted", "AcceptedPaymentStatus", "ApiProblem"), schemas.keySet());
        }
    }
}
