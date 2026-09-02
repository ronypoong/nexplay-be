-- 홈과 소식 목록의 정렬 점수를 미리 계산해 둔다.
--
-- 지금까지는 정렬 기준이 계산식이었다. 계산 결과가 어디에도 없으니 인덱스가
-- 탈 수 없고, 30건을 돌려주려고 game_event 11,305행을 전부 읽어 점수를 매기고
-- 전부 정렬한 뒤 11,275행을 버렸다. EXPLAIN ANALYZE 로 확인한 값이
-- 데워진 상태 77ms, 버퍼 풀이 비었을 때 1,465ms 다. 소식이 하루 60건씩 쌓이니
-- 이 값은 앞으로 계속 커진다.
--
-- 점수를 이루는 것들은 모두 하루에 한 번만 바뀐다.
--   종류 점수        고정
--   discovery_score  일일 동기화 때
--   summary_ko 유무  분류될 때
--   홍보성 표시      분류될 때
--   날짜 감점        하루 한 번
-- 그래서 하루 한 번 다시 계산해 두면 조회는 인덱스만 읽으면 된다.
-- 하루 안에서 순위가 고정되는데, 매거진에서는 그편이 오히려 맞다.

ALTER TABLE game_event ADD COLUMN feed_score DOUBLE NULL;

-- NULL 은 "목록에 내보내지 않는다" 는 뜻이다. archive_only 인 게임의 소식이
-- 여기 해당한다. 이렇게 두면 조회 쿼리가 game 을 조인할 이유가 없어져,
-- 인덱스 하나만 훑고 끝난다.
UPDATE game_event e
JOIN game g ON g.id = e.game_id
LEFT JOIN game_event_extraction x ON x.event_id = e.id AND x.prompt_version = 1
SET e.feed_score = CASE WHEN g.archive_only = 1 THEN NULL ELSE (
    CASE COALESCE(NULLIF(x.event_type, ''), e.type)
        WHEN 'RELEASE_DATE'  THEN 100
        WHEN 'DELAY'         THEN 100
        WHEN 'RELEASE'       THEN  95
        WHEN 'EXPANSION'     THEN  80
        WHEN 'DLC'           THEN  75
        WHEN 'DEMO'          THEN  72
        WHEN 'TRAILER'       THEN  70
        WHEN 'GAMEPLAY'      THEN  70
        WHEN 'BETA'          THEN  64
        WHEN 'PREORDER'      THEN  60
        WHEN 'DISCOUNT'      THEN  56
        WHEN 'MAJOR_UPDATE'  THEN  52
        WHEN 'ANNOUNCEMENT'  THEN  44
        WHEN 'PATCH'         THEN  16
        ELSE 40
    END
    + g.discovery_score / 5
    + CASE WHEN x.summary_ko IS NOT NULL AND x.summary_ko <> '' THEN 18 ELSE 0 END
    - LEAST(GREATEST(DATEDIFF(CURRENT_DATE, e.event_date), 0), 60) / 2
    - CASE WHEN COALESCE(x.is_marketing_noise, 0) = 1 THEN 30 ELSE 0 END
) END;

-- 정렬 순서 그대로 내림차순으로 만든다. 오름차순 인덱스를 거꾸로 읽어도 되지만,
-- 두 번째 열까지 방향이 같아야 역방향 읽기가 한 번에 끝난다.
CREATE INDEX idx_game_event_feed_score ON game_event (feed_score DESC, published_at DESC);
