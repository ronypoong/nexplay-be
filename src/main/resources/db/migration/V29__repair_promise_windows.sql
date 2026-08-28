-- 약속의 창은 발표보다 먼저 시작할 수 없다.
--
-- "2026년에 낸다" 를 2026년 5월에 발표해도 창의 시작이 1월 1일로 잡혔다. 규칙대로면
-- 맞지만, 밀린 기간을 창의 시작끼리 빼서 재다 보니 이미 지나간 날짜가 기준이 됐다.
-- Gallipoli 는 본문의 1915년(1차대전)을 약속 시점으로 잡아 40,774일 밀린 것으로 나왔다.
UPDATE game_promise
SET claimed_from = announced_at
WHERE claimed_from IS NOT NULL AND claimed_from < announced_at;

-- 창의 끝이 시작보다 앞서면 창이 뒤집힌 것이다. 판정 근거로 쓸 수 없으니 비운다.
UPDATE game_promise
SET claimed_from = NULL, claimed_to = NULL, claim_precision = 'NONE'
WHERE claimed_from IS NOT NULL AND claimed_to IS NOT NULL AND claimed_to < claimed_from;

-- 창이 바뀌었으니 판정을 다시 한다.
DELETE FROM game_promise_resolution;
