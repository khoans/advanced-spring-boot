package com.oms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test that verifies the entire Spring application context starts up correctly.
 *
 * @SpringBootTest loads the full application context (all beans, config, auto-configuration).
 * If any bean fails to wire, any config is invalid, or any startup error occurs, this test fails.
 * This catches configuration problems early (e.g., missing beans, circular dependencies,
 * invalid @ConfigurationProperties, bad SQL schemas).
 */
@SpringBootTest
class OmsApplicationTest {

    @Test
    void contextLoads() {
        // Intentionally empty — the test passes if the context starts without errors.
        // Spring Boot auto-configures H2 (dev profile) so no external DB is needed.
    }
}
