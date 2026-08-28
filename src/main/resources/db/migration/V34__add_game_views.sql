-- 조회수.
--
-- 날짜별로 쌓는다. 총계만 세면 "지금 뜨는 게임" 을 말할 수 없다 — 누적 1만은
-- 3년에 걸친 1만일 수도, 어제 하루의 1만일 수도 있고 둘은 완전히 다른 사실이다.
--
-- 기대 수와 함께 이 서비스에서만 생기는 기록이다. 나머지 자산은 전부 남이
-- 발표한 것을 모은 것이다.
CREATE TABLE game_view_daily (
    game_id BIGINT NOT NULL,
    view_date DATE NOT NULL,
    views INT NOT NULL DEFAULT 0,
    PRIMARY KEY (game_id, view_date),
    KEY idx_view_date (view_date DESC),
    CONSTRAINT fk_view_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
