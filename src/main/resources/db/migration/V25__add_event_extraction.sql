-- 이벤트 분류 결과를 원문과 함께 남긴다.
--
-- 규칙 기반 분류가 363건 중 203건(56%)을 ANNOUNCEMENT 로 뭉개고 있었다. 제목이
-- 영어 269 · 한국어 90 · 일본어 4 로 섞여 있고 마케팅 문구투성이라 규칙으로는
-- 못 고친다. 그 더미 안에 할인 15건, 예약/특전 8건이 묻혀 있다.
--
-- 중요한 건 분류 자체가 아니라 "언제 무엇을 근거로 그렇게 분류했는가" 를 남기는 것이다.
-- 모델도 프롬프트도 바뀌므로, 나중에 재분류하려면 판단 근거와 판본이 있어야 한다.
CREATE TABLE game_event_extraction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    confidence VARCHAR(10) NOT NULL,
    summary_ko VARCHAR(300) NULL,
    discount_percent INT NULL,
    mentioned_release_date DATE NULL,
    has_demo BIT(1) NOT NULL DEFAULT b'0',
    is_marketing_noise BIT(1) NOT NULL DEFAULT b'0',
    reason VARCHAR(500) NULL,
    model VARCHAR(60) NOT NULL,
    prompt_version INT NOT NULL DEFAULT 1,
    extracted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_extraction_event_version (event_id, prompt_version),
    KEY idx_extraction_type (event_type),
    CONSTRAINT fk_extraction_event FOREIGN KEY (event_id) REFERENCES game_event (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
