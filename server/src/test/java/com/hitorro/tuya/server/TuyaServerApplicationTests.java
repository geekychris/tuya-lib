package com.hitorro.tuya.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tuya-test",
        "spring.flyway.enabled=true"
})
class TuyaServerApplicationTests {

    @Test
    void contextLoads() {
        // Verifies all beans wire, JPA schema validates against Flyway migrations,
        // and the WebSocket handler registers without error.
    }
}
