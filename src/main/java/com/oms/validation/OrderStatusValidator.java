package com.oms.validation;

import com.oms.entity.OrderStatus;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class OrderStatusValidator implements ConstraintValidator<ValidOrderStatus, String> {

    private static final Set<String> VALID_STATUSES = Arrays.stream(OrderStatus.values())
            .map(Enum::name)
            .collect(Collectors.toSet());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return VALID_STATUSES.contains(value.toUpperCase());
    }
}
