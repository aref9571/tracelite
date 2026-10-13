package com.tracelite.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
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

    @Test
    void cleanDatabaseIsMigratedToTheCurrentSchema() {
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion(), MigrationInfo::getState)
                .containsExactly(tuple("1", MigrationState.SUCCESS), tuple("2", MigrationState.SUCCESS));
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' "
                        + "and table_name in ('issues', 'events') order by table_name", String.class))
                .containsExactly("events", "issues");
        assertThat(jdbcTemplate.queryForList(
                "select extname from pg_extension where extname = 'pg_trgm'", String.class))
                .containsExactly("pg_trgm");
    }
}
