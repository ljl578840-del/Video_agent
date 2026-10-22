CREATE TABLE learning_video_segments (
    id CHAR(64) NOT NULL PRIMARY KEY,
    media_id BIGINT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    transcript LONGTEXT NOT NULL,
    ocr_text LONGTEXT NOT NULL,
    UNIQUE KEY uk_learning_segment_range (id, start_ms, end_ms),
    CONSTRAINT fk_learning_segment_media FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE,
    CONSTRAINT ck_learning_segment_range CHECK (start_ms >= 0 AND end_ms > start_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE learning_exercises (
    id CHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    scope_digest CHAR(64) NOT NULL,
    segment_id CHAR(64) NOT NULL,
    segment_start_ms BIGINT NOT NULL,
    segment_end_ms BIGINT NOT NULL,
    timestamp_ms BIGINT NOT NULL,
    prompt TEXT NOT NULL,
    expected_answer VARCHAR(128) NOT NULL,
    knowledge_point TEXT NOT NULL,
    evidence_text TEXT NOT NULL,
    UNIQUE KEY uk_learning_exercise_owner (id, user_id),
    KEY idx_learning_exercise_scope (user_id, scope_digest),
    CONSTRAINT fk_learning_exercise_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_exercise_segment FOREIGN KEY (segment_id, segment_start_ms, segment_end_ms)
        REFERENCES learning_video_segments(id, start_ms, end_ms) ON DELETE CASCADE,
    CONSTRAINT ck_learning_exercise_timestamp CHECK (timestamp_ms >= segment_start_ms AND timestamp_ms < segment_end_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE learning_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_id CHAR(36) NOT NULL,
    exercise_id CHAR(64) NOT NULL,
    submitted_answer VARCHAR(128) NOT NULL,
    correct BOOLEAN NOT NULL,
    answered_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_learning_attempt_request (user_id, request_id),
    KEY idx_learning_attempt_history (user_id, id),
    CONSTRAINT fk_learning_attempt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_learning_attempt_exercise FOREIGN KEY (exercise_id, user_id) REFERENCES learning_exercises(id, user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
