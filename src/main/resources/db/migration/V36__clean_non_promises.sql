-- 약속이 아닌 것을 걷어낸다.
--
-- 패치노트 하나에서 "약속" 21건이 나왔다. 전부 이미 적용된 변경이었다.
--   "Players now resurrect upon death!"
--   "Ghost form lasts for 45 seconds"
-- 프롬프트에 "이미 일어난 일은 약속이 아니다" 라고 적어 뒀는데 패치노트에서
-- 무너졌다. 패치노트는 정의상 이미 한 일의 기록이라 앞으로 할 일이 없다.
--
-- 그리고 시점 없는 약속은 영원히 판정할 수 없다. CONTENT 742건 중 535건이
-- 시점이 아예 없었다. 채점할 수 없는 것을 모으면 대조표가 아니라 목록이 된다.
--
-- 기록을 지우는 것이 아니라 잘못 뽑은 것을 되돌리는 것이다. 원문은 raw_item 에
-- 그대로 있으므로 판본을 올리면 언제든 다시 뽑을 수 있다.

-- 패치노트에서 나온 약속
DELETE p FROM game_promise p
JOIN game_event e ON e.id = p.event_id
WHERE e.type IN ('PATCH', 'MAJOR_UPDATE');

-- 시점이 없어 채점할 수 없는 약속. 출시일과 한국어 지원은 시점이 없어도
-- 각각 실제 출시일·언어 이력으로 판정할 수 있어 남긴다.
DELETE FROM game_promise
WHERE claimed_from IS NULL AND claimed_to IS NULL
  AND claim_type NOT IN ('RELEASE_DATE', 'KOREAN_SUPPORT');

-- 남은 약속에 맞춰 판정을 다시 한다.
DELETE FROM game_promise_resolution;
