package com.tracelite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class TraceLiteApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void applicationContextStarts() {
        assertThat(applicationContext.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("tracelite");
    }
}
