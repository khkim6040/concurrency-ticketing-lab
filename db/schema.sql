CREATE TABLE IF NOT EXISTS event (
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id    VARCHAR(36) NOT NULL,
    total     INT NOT NULL,
    remaining INT NOT NULL,
    version   BIGINT NOT NULL DEFAULT 0
);
