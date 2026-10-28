package com.practice.cashflow;

import com.practice.cashflow.adapter.in.web.PaymentSubmissionRequest;
import com.practice.cashflow.application.AcceptPaymentCommand;
import com.practice.cashflow.application.AcceptPaymentService;
import com.practice.cashflow.application.PaymentAcceptance;
import com.practice.cashflow.application.port.out.PaymentAcceptanceStore;
import com.practice.cashflow.domain.PaymentCurrency;
import com.practice.cashflow.domain.PaymentProvider;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentAcceptanceTypesTest {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PaymentAcceptanceStore store = mock(PaymentAcceptanceStore.class);
    private final AcceptPaymentService service = new AcceptPaymentService(store);

    static Stream<Arguments> supportedCombinations() {
        return Arrays.stream(PaymentCurrency.values())
                .flatMap(currency -> Arrays.stream(PaymentProvider.values())
                        .map(provider -> Arguments.of(currency, provider)));
    }

    @ParameterizedTest
    @MethodSource("supportedCombinations")
    void convertsValidatedJsonToTypedCommandAndPreservesTheStoreResult(
            PaymentCurrency currency, PaymentProvider provider) {
        String body = """
                {"amountMinor":2500,"currency":"%s","provider":"%s",
                 "paymentMethodToken":"pm_funded","merchantReference":"order-42"}
                """.formatted(currency.name(), provider.name());
        var request = JSON.readValue(body, PaymentSubmissionRequest.class);
        assertTrue(VALIDATORS.getValidator().validate(request).isEmpty());

        var command = request.toCommand("10000000-0000-4000-8000-000000000001", "request-42");
        assertEquals(new AcceptPaymentCommand("10000000-0000-4000-8000-000000000001", "request-42", 2500L,
                currency, provider, "pm_funded", "order-42"), command);
        var expected = new PaymentAcceptance(UUID.randomUUID(),
                Instant.parse("2026-10-21T09:00:00Z"), true);
        when(store.acceptAtomically(command)).thenReturn(expected);

        assertSame(expected, service.accept(command));
        verify(store).acceptAtomically(command);
        verifyNoMoreInteractions(store);
        assertEquals(JSON.readTree(body), JSON.readTree(JSON.writeValueAsString(request)));
    }

    static Stream<Arguments> invalidProviderOrCurrency() {
        return Stream.of(
                Arguments.of("currency", "GBP", "STRIPE"),
                Arguments.of("currency", "eur", "STRIPE"),
                Arguments.of("currency", "EUR ", "STRIPE"),
                Arguments.of("currency", "", "STRIPE"),
                Arguments.of("currency", null, "STRIPE"),
                Arguments.of("provider", "EUR", "UNKNOWN"),
                Arguments.of("provider", "EUR", "stripe"),
                Arguments.of("provider", "EUR", " STRIPE"),
                Arguments.of("provider", "EUR", ""),
                Arguments.of("provider", "EUR", null)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidProviderOrCurrency")
    void retainsStrictHttpValidation(String field, String currency, String provider) {
        var request = new PaymentSubmissionRequest(2500L, currency, provider, "pm_funded", "order-42");
        var violations = VALIDATORS.getValidator().validate(request);
        assertTrue(violations.stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"currency", "provider"})
    void rejectsMissingEnumBeforeCallingTheStore(String missingField) {
        var command = new AcceptPaymentCommand("10000000-0000-4000-8000-000000000001", "request-42", 2500L,
                missingField.equals("currency") ? null : PaymentCurrency.EUR,
                missingField.equals("provider") ? null : PaymentProvider.STRIPE,
                "pm_funded", "order-42");
        var failure = assertThrows(IllegalArgumentException.class, () -> service.accept(command));
        assertEquals(missingField + " is required.", failure.getMessage());
        verifyNoInteractions(store);
    }

    static Stream<Arguments> invalidPaymentRequests() {
        return Stream.of(
                Arguments.of(
                        "amountMinor",
                        new PaymentSubmissionRequest(
                                0L,
                                "EUR",
                                "STRIPE",
                                "pm_funded",
                                "order-001"
                )
        ),
                Arguments.of("amountMinor",
                        new PaymentSubmissionRequest(
                                -1L, "EUR", "STRIPE", "pm_funded", "order-001")),

                Arguments.of("currency",
                        new PaymentSubmissionRequest(
                                2500L, "GBP", "STRIPE", "pm_funded", "order-001")),

                Arguments.of("provider",
                        new PaymentSubmissionRequest(
                                2500L, "EUR", "UNKNOWN", "pm_funded", "order-001")),

                Arguments.of("paymentMethodToken",
                        new PaymentSubmissionRequest(
                                2500L, "EUR", "STRIPE", "invalid-token", "order-001")),

                Arguments.of("merchantReference",
                        new PaymentSubmissionRequest(
                                2500L, "EUR", "STRIPE", "pm_funded", ""))
        );
    }


    @ParameterizedTest(name = "{index}: rejects invalid {0}")
    @MethodSource("invalidPaymentRequests")
    void rejectInvalidPaymentField (
            String expectedField,
            PaymentSubmissionRequest request
    ) {
        var violations = VALIDATORS.getValidator().validate(request);

        assertTrue(
                violations.stream().anyMatch(
                 violation ->
                 violation.getPropertyPath().toString().equals(expectedField)
                ),
                () -> "Expected a validation error for " + expectedField
        );

    }

    @Test
    void zeroAmountIsRejected() {
        var request = new PaymentSubmissionRequest(
                0L,
                "EUR",
                "STRIPE",
                "pm_funded",
                "order-001"
        );

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var errors = factory.getValidator().validate(request);

            assertTrue(errors.stream().anyMatch(error ->
                    error.getPropertyPath().toString()
                            .equals("amountMinor")
            ));
        }
    }

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }
}
