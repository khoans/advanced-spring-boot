package com.oms.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit test for our custom Bean Validation constraint validator.
 *
 * This is a plain unit test — no Spring context needed. We instantiate the validator
 * directly and mock the ConstraintValidatorContext (which we don't use, but the
 * isValid() signature requires it).
 *
 * Tests cover the contract of @ValidOrderStatus:
 *  - All five enum values are accepted (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED)
 *  - Case-insensitive matching works (e.g., "pending" is valid)
 *  - null is treated as valid (following Bean Validation convention — use @NotNull separately)
 *  - Unrecognized strings are rejected
 */
class OrderStatusValidatorTest {

    private OrderStatusValidator validator;
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        // Create a fresh validator per test; mock context since the validator never calls it
        validator = new OrderStatusValidator();
        context = mock(ConstraintValidatorContext.class);
    }

    @Test
    void shouldReturnTrueForValidStatus() {
        // Every value from the OrderStatus enum must be accepted
        assertThat(validator.isValid("PENDING", context)).isTrue();
        assertThat(validator.isValid("CONFIRMED", context)).isTrue();
        assertThat(validator.isValid("SHIPPED", context)).isTrue();
        assertThat(validator.isValid("DELIVERED", context)).isTrue();
        assertThat(validator.isValid("CANCELLED", context)).isTrue();
    }

    @Test
    void shouldReturnTrueForLowercaseValidStatus() {
        // Validator uppercases before checking, so "pending" → "PENDING" is valid
        assertThat(validator.isValid("pending", context)).isTrue();
        assertThat(validator.isValid("confirmed", context)).isTrue();
    }

    @Test
    void shouldReturnTrueForNullValue() {
        // Bean Validation convention: null is valid — use @NotNull to reject nulls
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    void shouldReturnFalseForInvalidStatus() {
        // Strings not matching any OrderStatus enum value must be rejected
        assertThat(validator.isValid("INVALID", context)).isFalse();
        assertThat(validator.isValid("", context)).isFalse();
        assertThat(validator.isValid("PROCESSING", context)).isFalse();
    }
}
