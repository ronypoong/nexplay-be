-- 왜 기대하는지 한 줄.
--
-- 댓글창이 아니다. 게임 하나에 묶인 한 줄이고, 서로 대화하지 않는다. 그래서
-- 싸울 구조가 아니고 관리 부담도 작다.
--
-- 그리고 이건 나중에 채점된다. 게임사의 약속을 기록하고 결과와 대조하듯,
-- 사용자의 기대도 기록하고 실제와 대조한다 — 같은 구조다.
ALTER TABLE game_anticipation
    ADD COLUMN reason VARCHAR(140) NULL AFTER voter_hash,
    ADD COLUMN reason_at TIMESTAMP(6) NULL AFTER reason;

-- 게임 상세에서 최근 이유부터 보여 준다.
CREATE INDEX idx_anticipation_reason ON game_anticipation (game_id, reason_at DESC);
