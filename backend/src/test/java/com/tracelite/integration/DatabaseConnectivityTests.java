package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class DatabaseConnectivityTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void connectsToPostgresql17() {
        Integer serverVersion = jdbcTemplate.queryForObject(
                "select current_setting('server_version_num')::int", Integer.class);

        assertThat(serverVersion).isBetween(170000, 179999);
    }

    @Test
    void flywayUsesTheMigrationLocationAndHasNoPendingMigrations() {
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Location::getDescriptor)
                .containsExactly("classpath:db/migration");
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
    }
}
