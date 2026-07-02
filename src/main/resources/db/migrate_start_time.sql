-- Migration: convert sudoku_boards.start_time from BIGINT (epoch millis) to TIMESTAMP
--
-- Why: Hibernate 6 maps LocalDateTime to TIMESTAMP and binds a timestamp string.
--      PostgreSQL can't store a timestamp string in a BIGINT column, so every INSERT
--      fails with: invalid input syntax for type bigint.
--      The scheduler query (CURRENT_TIMESTAMP - b.start_time) also fails because
--      PostgreSQL won't subtract BIGINT from TIMESTAMP.
--
-- Run once against your local database before starting the app:
--   "C:\Program Files\PostgreSQL\16\bin\psql.exe" -U postgres -d sudokupro -f src\main\resources\db\migrate_start_time.sql
--
-- Idempotent: the DO block skips the ALTER if the column is already TIMESTAMP.

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
