-- GOTY 아카이브.
--
-- 카탈로그는 "올해 신작" 기준으로 만들어져 2014~2025 수상작이 하나도 없다.
-- 그렇다고 위쳐3 와 오버워치를 디스커버와 캘린더에 섞으면 서비스 성격이 바뀐다.
-- archive_only 로 표시해 GOTY 화면과 상세에서만 보이게 한다.
ALTER TABLE game ADD COLUMN archive_only BIT(1) NOT NULL DEFAULT b'0';
CREATE INDEX idx_game_archive ON game (archive_only);

-- 수상·후보 기록. 출처는 Wikidata 이며 game 에 없는 작품도 이름으로 남길 수 있어야
-- 하므로 game_id 를 NULL 허용으로 둔다.
CREATE TABLE game_award (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NULL,
    wikidata_id VARCHAR(30) NULL,
    title VARCHAR(180) NOT NULL,
    award_name VARCHAR(120) NOT NULL,
    result VARCHAR(20) NOT NULL,
    award_year INT NOT NULL,
    source_name VARCHAR(80) NOT NULL DEFAULT 'Wikidata',
    source_url VARCHAR(500) NULL,
    verified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_award (wikidata_id, award_name, result, award_year),
    KEY idx_award_year (award_year, result),
    KEY idx_award_game (game_id),
    CONSTRAINT fk_award_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
