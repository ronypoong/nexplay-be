-- 약속을 찾아본 적이 있다는 사실 자체를 남긴다.
--
-- 예전에는 game_promise 에 행이 있는지로 "이미 봤는가" 를 판단했다. 그런데 대부분의
-- 글에는 약속이 없어서 행이 안 생기고, 그러면 다음 실행에서 또 모델에게 보낸다.
-- 매일 같은 글에 같은 돈을 쓰면서, LIMIT 에 걸려 오래된 글에는 영영 닿지 못한다.
--
-- 찾아본 결과가 "없음" 인 것도 결과다.
CREATE TABLE game_promise_scan (
    event_id BIGINT NOT NULL,
    prompt_version INT NOT NULL,
    promises_found INT NOT NULL DEFAULT 0,
    model VARCHAR(60) NULL,
    scanned_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (event_id, prompt_version),
    CONSTRAINT fk_promise_scan_event FOREIGN KEY (event_id) REFERENCES game_event (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 이미 약속이 뽑힌 이벤트는 당연히 찾아본 것이다. 소급해서 적어 둔다.
INSERT INTO game_promise_scan (event_id, prompt_version, promises_found, model)
SELECT p.event_id, p.prompt_version, COUNT(*), MAX(p.model)
FROM game_promise p
WHERE p.event_id IS NOT NULL
GROUP BY p.event_id, p.prompt_version;
