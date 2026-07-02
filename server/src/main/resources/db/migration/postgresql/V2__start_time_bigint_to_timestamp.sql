-- V2: convert sudoku_boards.start_time from BIGINT (epoch millis) to TIMESTAMP.
-- Applies only to pre-Flyway databases (fresh ones get TIMESTAMP from V1; the DO
-- block below is idempotent). PostgreSQL-specific — lives in the {vendor} location.
-- Migrated from db/migrate_start_time.sql, which was applied by hand before Flyway.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'sudoku_boards'
          AND column_name = 'start_time'
          AND data_type = 'bigint'
    ) THEN
        ALTER TABLE sudoku_boards
            ALTER COLUMN start_time
            TYPE TIMESTAMP WITHOUT TIME ZONE
            USING TO_TIMESTAMP(start_time / 1000.0);

        RAISE NOTICE 'Migrated start_time from BIGINT to TIMESTAMP.';
    ELSE
        RAISE NOTICE 'start_time is already TIMESTAMP — no migration needed.';
    END IF;
END
$$;
