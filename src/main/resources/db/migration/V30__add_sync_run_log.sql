-- 매일 무엇이 돌았고 무엇이 실패했는지 남긴다.
--
-- 지금은 단계 실패가 로그에만 찍히고 아무도 보지 않는다. 이 서비스의 값은 하루도
-- 빠뜨리지 않고 쌓이는 데서 나오는데, 조용히 멈추면 그 날들은 영영 못 되찾는다.
-- 실제로 원문 본문을 저장하지 않던 며칠치가 그렇게 사라졌다.
CREATE TABLE sync_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    step VARCHAR(60) NOT NULL,
    status VARCHAR(20) NOT NULL,
    detail VARCHAR(500) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    KEY idx_sync_run_step (step, started_at DESC),
    KEY idx_sync_run_started (started_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
