-- 사용자가 직접 남기는 유일한 기록.
--
-- 지금까지의 자산은 전부 남이 발표한 것을 모은 것이다. 이건 여기서만 생긴다.
--
-- 원본 IP 는 저장하지 않는다. 소금을 섞은 해시만 남기고, 그 해시로 같은 사람이
-- 두 번 누르는 것만 막는다. 누가 눌렀는지는 우리도 알 수 없다.
CREATE TABLE game_anticipation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    voter_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_anticipation_voter (game_id, voter_hash),
    KEY idx_anticipation_game (game_id),
    -- 같은 사람이 하루에 몇 개나 눌렀는지 세려면 해시+시각으로 찾을 수 있어야 한다.
    KEY idx_anticipation_voter_time (voter_hash, created_at),
    CONSTRAINT fk_anticipation_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
