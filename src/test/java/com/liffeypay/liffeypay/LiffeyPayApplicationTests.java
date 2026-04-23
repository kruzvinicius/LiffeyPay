package com.liffeypay.liffeypay;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires a running PostgreSQL instance — run as integration test with a real DB")
class LiffeyPayApplicationTests {

    @Test
    void contextLoads() {
    }
}
