# ADR 0003: JDBC + Flyway, H2 by default, PostgreSQL optional

**Status:** accepted

Workflows, artifacts and agent executions are stored via `JdbcTemplate` with JSON columns. The default is a local H2 file DB for zero-setup runs; `compose.yaml` offers PostgreSQL (`DB_URL`, `DB_USER`, `DB_PASSWORD`). Migrations live in `db/migration` (Flyway). Interrupted workflows are marked FAILED/INTERRUPTED on startup; there is no resume.
