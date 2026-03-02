package com.oms.config.routing;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that routes methods annotated with {@link ReadOnly} to the read replica.
 * Runs at highest precedence so the datasource context is set before @Transactional opens a connection.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReadOnlyDataSourceAspect {

    @Around("@annotation(com.oms.config.routing.ReadOnly)")
    public Object routeToReadReplica(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            DataSourceContextHolder.set(DataSourceType.READ_REPLICA);
            return joinPoint.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
