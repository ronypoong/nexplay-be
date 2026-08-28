-- 한 발표가 같은 종류의 약속을 여러 개 할 수 있다.
--
-- (event_id, claim_type, prompt_version) 으로 묶어 두니 "A 도 넣고 B 도 넣겠다" 는
-- 발표에서 CONTENT 약속 여섯 개가 한 줄로 뭉개졌다. 실제로 Forza Horizon 6 발표에서
-- 모델이 여섯 개를 찾았는데 하나만 남았다.
--
-- 약속의 내용까지 넣어 구분한다. 값을 그대로 키에 넣으면 인덱스가 길어지므로
-- 해시를 쓴다. 같은 글을 다시 뽑아도 같은 해시가 나오므로 중복 저장은 여전히 막힌다.
ALTER TABLE game_promise ADD COLUMN claim_key CHAR(64) NULL AFTER claim_type;

UPDATE game_promise SET claim_key = SHA2(CONCAT(claim_type, '|', claimed_value), 256)
WHERE claim_key IS NULL;

ALTER TABLE game_promise MODIFY COLUMN claim_key CHAR(64) NOT NULL;

-- 새 인덱스를 먼저 만든다. 옛 인덱스는 event_id 로 시작해서 외래키가 쓰고 있고,
-- 대신할 인덱스 없이 지우려 하면 MySQL 이 거부한다. 새 것도 event_id 로 시작하므로
-- 만들어 두면 외래키가 그쪽을 쓴다.
ALTER TABLE game_promise ADD UNIQUE KEY uk_promise_event_claim (event_id, claim_key, prompt_version);
ALTER TABLE game_promise DROP INDEX uk_promise_event_type;

-- 뭉개진 탓에 잃어버린 약속들을 다시 뽑게 한다. 찾은 수와 저장된 수가 다른
-- 이벤트의 검사 기록을 지우면 다음 실행에서 그 글부터 다시 본다.
DELETE sc FROM game_promise_scan sc
WHERE sc.promises_found > (
    SELECT COUNT(*) FROM game_promise p
    WHERE p.event_id = sc.event_id AND p.prompt_version = sc.prompt_version
);
