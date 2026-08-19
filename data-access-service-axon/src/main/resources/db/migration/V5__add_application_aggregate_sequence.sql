ALTER TABLE application_current_state
    ADD COLUMN aggregate_sequence BIGINT NOT NULL DEFAULT 0;