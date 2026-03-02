package com.oms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = OrderStatusValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidOrderStatus {

    String message() default "Invalid order status. Allowed values: PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
