-- 약속과 결과의 대조표.
--
-- 게임 업계는 약속으로 굴러간다 — 출시일, 한국어 지원, 로드맵. 그리고 상당수가
-- 지켜지지 않는다. 아무도 이걸 체계적으로 대조하지 않는 이유는 단순하다:
-- 대조하려면 약속한 시점에 그 약속을 기록해뒀어야 하는데, Steam RSS 는 과거 글을
-- 다시 주지 않는다.
--
-- 실제로 우리 아카이브에 이런 게 이미 남아 있다.
--   2024-12-11  "Golf With Your Friends 2! Coming 2025!"
--   2026-04-23  "Golf With Your Friends 2 | Coming Fall 2026!"
-- 1년 넘게 밀렸는데 release_revision 에는 한 줄도 없다. Wikidata 가 이 게임을
-- 그냥 "2026년" 으로 갖고 있기 때문이다. 진짜 정보는 공식 발표 본문 안에 있다.

-- 약속. 한 번 추출하면 바뀌지 않는다 — 그때 그렇게 말했다는 사실 자체이므로.
CREATE TABLE game_promise (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    event_id BIGINT NULL,
    claim_type VARCHAR(30) NOT NULL,
    claimed_value VARCHAR(200) NOT NULL,
    claimed_from DATE NULL,
    claimed_to DATE NULL,
    claim_precision VARCHAR(20) NOT NULL,
    source_quote VARCHAR(500) NULL,
    announced_at DATE NOT NULL,
    -- LIVE  : 원문을 보관한 상태에서 뽑은 약속. 대조표의 근거가 된다.
    -- BACKTEST : 제목만 남은 과거 소식에서 소급 추출한 것. 참고용이며 점수에서 뺀다.
    -- foresee 가 예측을 LIVE/BACKTEST 로 가르는 것과 같은 이유다. 섞으면 못 믿는다.
    provenance VARCHAR(10) NOT NULL,
    model VARCHAR(60) NULL,
    prompt_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_promise_event_type (event_id, claim_type, prompt_version),
    KEY idx_promise_game (game_id, claim_type, announced_at),
    KEY idx_promise_provenance (provenance),
    CONSTRAINT fk_promise_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE,
    CONSTRAINT fk_promise_event FOREIGN KEY (event_id) REFERENCES game_event (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 채점 결과. 약속과 달리 현실이 바뀌면 다시 계산된다.
CREATE TABLE game_promise_resolution (
    promise_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    actual_value VARCHAR(200) NULL,
    actual_date DATE NULL,
    slip_days INT NULL,
    evidence VARCHAR(300) NULL,
    evaluated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (promise_id),
    KEY idx_resolution_status (status),
    CONSTRAINT fk_resolution_promise FOREIGN KEY (promise_id) REFERENCES game_promise (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
