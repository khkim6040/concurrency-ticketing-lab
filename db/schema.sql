CREATE TABLE IF NOT EXISTS event (
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id    VARCHAR(36) NOT NULL,
    total     INT NOT NULL,
    remaining INT NOT NULL,
    version   BIGINT NOT NULL DEFAULT 0
);

-- 유니크 인덱스 ux_reservation_seat (event_id, seat_no)는 web이 실행 시작 시 CREATE/DROP으로 토글한다.
CREATE TABLE IF NOT EXISTS reservation (
    id       BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    seat_no  INT    NOT NULL,
    user_id  BIGINT NOT NULL
);
