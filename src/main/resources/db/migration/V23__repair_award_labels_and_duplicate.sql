-- 1) 출시 라벨에 코틀린 템플릿이 문자열 그대로 저장됐다.
--
-- backfillReleaseDate 가 "${'$'}{year}년 출시" 로 쓰여 있었다. ${'$'} 는 리터럴 달러를
-- 만들므로 결과가 보간되지 않고 "${year}년 출시" 가 그대로 들어갔다. 83개 행이
-- 화면에 그 문자열을 그대로 보여주고 있었다.
UPDATE game
SET release_label = CONCAT(YEAR(release_date), '년 출시')
WHERE release_label LIKE '%${year}년 출시' AND release_date IS NOT NULL;

UPDATE game
SET release_label = CONCAT(YEAR(release_date), '년 출시 예정')
WHERE release_label LIKE '%${year}년 출시 예정' AND release_date IS NOT NULL;

-- 2) 수상 동기화가 GTA VI 를 중복 생성했다.
--
-- 카탈로그의 원본(V11 에서 넣은 것)에 wikidata_id 가 없어서 매칭에 실패했고,
-- Steam appId 도 양쪽 다 없어 유니크 제약도 걸리지 않았다.
-- 원본에 Wikidata 식별자를 달아 앞으로는 매칭되게 하고, 수상 기록을 원본으로 옮긴 뒤
-- 중복 행을 지운다.
UPDATE game g
JOIN (SELECT id FROM game WHERE wikidata_id = 'Q23648408') dup
SET g.wikidata_id = NULL
WHERE g.id = dup.id;

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
