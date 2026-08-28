-- Steam 리뷰 수를 시간에 따라 남긴다.
--
-- 판매량 대리 지표인데, 과거 이력을 공짜로 주는 곳이 없다. 오늘부터 찍어 두면
-- 그 자체가 나중에 살 수 없는 자산이 된다.
--
-- 값이 바뀌었을 때만 한 줄 남긴다. 매일 같은 값을 넣으면 그래프에 가짜 평평한
-- 구간이 생기고, 실제로 관측한 것과 그냥 반복한 것을 구분할 수 없게 된다.
ALTER TABLE game ADD COLUMN steam_review_count BIGINT NULL AFTER follower_count;

CREATE TABLE game_review_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    review_count BIGINT NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_history (game_id, review_count, observed_at),
    KEY idx_review_history_game (game_id, observed_at DESC),
    CONSTRAINT fk_review_history_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
