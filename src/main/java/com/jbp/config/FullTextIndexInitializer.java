package com.jbp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * Ensures the MySQL FULLTEXT indexes used by job search exist. Title and description
 * are indexed separately so search can weight a title match higher than a body match.
 * Hibernate does not create FULLTEXT indexes under ddl-auto=update, so we create them
 * here, idempotently, at startup. Runs only on MySQL (skips H2 and other databases).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FullTextIndexInitializer implements ApplicationRunner {

    private static final String JOBS_TABLE = "jobs";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        String product = jdbcTemplate.execute((Connection connection) ->
                connection.getMetaData().getDatabaseProductName());

        if (product == null || !product.toLowerCase().contains("mysql")) {
            log.info("Skipping FULLTEXT index setup on non-MySQL database: {}", product);
            return;
        }

        // Migrate from the earlier combined index to per-column indexes (idempotent).
        dropIndexIfExists(JOBS_TABLE, "ft_jobs_title_description");
        ensureFullTextIndex(JOBS_TABLE, "ft_jobs_title", "title");
        ensureFullTextIndex(JOBS_TABLE, "ft_jobs_description", "description");
    }

    private boolean indexExists(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, indexName);
        return count != null && count > 0;
    }

    private void ensureFullTextIndex(String table, String indexName, String columns) {
        if (indexExists(table, indexName)) {
            return;
        }
        jdbcTemplate.execute("CREATE FULLTEXT INDEX " + indexName + " ON " + table + " (" + columns + ")");
        log.info("Created FULLTEXT index {} on {}({})", indexName, table, columns);
    }

    private void dropIndexIfExists(String table, String indexName) {
        if (!indexExists(table, indexName)) {
            return;
        }
        jdbcTemplate.execute("DROP INDEX " + indexName + " ON " + table);
        log.info("Dropped obsolete index {} on {}", indexName, table);
    }
}
