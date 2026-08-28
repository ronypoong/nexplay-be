-- 주인장이 직접 고르는 "눈여겨보는 작품".
--
-- 나머지 목록은 전부 점수와 날짜로 자동 정렬된다. 이 표만 사람이 고른다.
-- 알고리즘이 못 하는 것 — 왜 이 게임을 기다리는지 한 줄로 말하는 것 — 이 핵심이라
-- note 는 비워둘 수 없게 한다.
CREATE TABLE editor_pick (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    note VARCHAR(300) NOT NULL,
    headline VARCHAR(80) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    active BIT(1) NOT NULL DEFAULT b'1',
    picked_at DATE NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_editor_pick_game (game_id),
    KEY idx_editor_pick_order (active, sort_order),
    CONSTRAINT fk_editor_pick_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
