-- 한국어 지원 이력.
--
-- game_language_support 는 현재값만 들고 덮어쓴다. 그래서 "이 게임이 언제부터
-- 한국어를 지원했나", "이 퍼블리셔는 출시 후 평균 몇 개월 만에 한국어를 붙이나"
-- 를 나중에 물을 수 없다. 덮어쓴 과거는 되돌릴 방법이 없다.
--
-- 시간이 지날수록 값이 오르는 자산은 현재값이 아니라 변화 기록이다.
-- 오늘부터 남긴다.
CREATE TABLE game_language_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    language_code VARCHAR(20) NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    previous_text BIT(1) NULL,
    previous_audio BIT(1) NULL,
    new_text BIT(1) NULL,
    new_audio BIT(1) NULL,
    source_name VARCHAR(80) NOT NULL DEFAULT 'Steam Store',
    source_url VARCHAR(500) NULL,
    observed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_lang_history_game (game_id, language_code, observed_at),
    KEY idx_lang_history_time (observed_at),
    CONSTRAINT fk_lang_history_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 지금 알고 있는 상태를 기준선으로 한 번 남긴다. 이게 없으면 첫 변경이
-- 관측될 때까지 "언제부터였는지" 를 말할 수 없다.
INSERT INTO game_language_history (game_id, language_code, change_type, new_text, new_audio, source_name, observed_at)
SELECT game_id, language_code, 'BASELINE', text_supported, audio_supported, source_name, verified_at
FROM game_language_support;
