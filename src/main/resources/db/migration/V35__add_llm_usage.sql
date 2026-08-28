-- 모델에 쓴 토큰을 남긴다.
--
-- 하루 상한을 메모리에만 두고 있었다. 배포할 때마다 컨테이너가 새로 뜨면서
-- 카운터가 0 으로 돌아갔고, 하루에 열 번 배포하면 상한이 열 번 풀렸다.
-- 폭주를 막으려고 둔 장치가 정작 폭주를 못 막는 상태였다.
--
-- 그리고 얼마나 썼는지 아무도 몰랐다. 추정만 할 수 있고 확인할 수는 없었다.
CREATE TABLE llm_usage (
    usage_date DATE NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    model VARCHAR(60) NOT NULL,
    calls INT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (usage_date, purpose, model),
    KEY idx_llm_usage_date (usage_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
