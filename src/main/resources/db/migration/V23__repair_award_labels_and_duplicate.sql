-- 1) 출시 라벨에 코틀린 문자열 템플릿이 보간되지 않고 그대로 저장됐다.
--    화면에 달러-중괄호-year 문자열이 그대로 떴다. 83개 행이 그랬다.
--
--    주의: Flyway 는 SQL 안의 달러-중괄호를 자기 플레이스홀더로 해석한다.
--    그래서 아래 패턴은 CONCAT 으로 조립한다. 리터럴로 쓰면 마이그레이션 파싱이
--    깨지고 애플리케이션이 기동하지 못한다.
UPDATE game
SET release_label = CONCAT(YEAR(release_date), '년 출시')
WHERE release_date IS NOT NULL
  AND release_label LIKE CONCAT('%', CHAR(36), '{year}년 출시');

UPDATE game
SET release_label = CONCAT(YEAR(release_date), '년 출시 예정')
WHERE release_date IS NOT NULL
  AND release_label LIKE CONCAT('%', CHAR(36), '{year}년 출시 예정');

-- 남은 것이 있으면(날짜가 없어 위에서 못 고친 경우) 최소한 깨진 문자열은 치운다.
UPDATE game
SET release_label = '출시일 미정'
WHERE release_label LIKE CONCAT('%', CHAR(36), '{year}%');

-- 2) 수상 동기화가 GTA VI 를 중복 생성했다.
--
-- 카탈로그의 원본(V11)에 wikidata_id 가 없어 매칭에 실패했고, Steam appId 는
-- 양쪽 다 없어서 유니크 제약도 걸리지 않았다.
UPDATE game SET wikidata_id = NULL WHERE wikidata_id = 'Q23648408';

UPDATE game SET wikidata_id = 'Q23648408'
WHERE slug = 'grand-theft-auto-vi' AND wikidata_id IS NULL;

UPDATE game_award a
JOIN game g ON g.slug = 'grand-theft-auto-vi'
SET a.game_id = g.id
WHERE a.wikidata_id = 'Q23648408';

DELETE FROM game
WHERE slug <> 'grand-theft-auto-vi'
  AND wikidata_id IS NULL
  AND title = '그랜드 테프트 오토 VI';
