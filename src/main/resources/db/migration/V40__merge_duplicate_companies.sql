-- 같은 회사가 여러 줄로 앉아 있던 것을 합친다.
--
-- 한국어 레이더의 퍼블리셔 순위에 "Bandai Namco Entertainment" 와
-- "반다이 남코 엔터테인먼트" 가 나란히 떴다. 간판 화면에 같은 회사가 두 번 뜨면
-- 그 아래 숫자 전체의 신뢰가 깎인다.
--
-- 원인은 resolveCompany 가 이름으로만 맞춰 보기 때문이다. Steam 을 l=korean 으로
-- 부르면 개발사·퍼블리셔 이름이 한국어로 오는데, 그게 Wikidata 가 넣어 둔
-- 영문 이름과 글자가 달라 새 줄이 생긴다. 슬러그를 지을 때 한글이 전부 떨어져
-- 나가 company-숫자, 심지어 game-숫자 같은 이름이 붙었다.
--
-- 여기서는 쌓인 것을 치운다. 들어오는 쪽을 막는 것은 코드의 몫이다.
--
-- 합치는 대상은 두 가지뿐이다.
--   (1) 이름이 글자까지 똑같은 줄. 판단이 필요 없다.
--   (2) 영문 정본이 실제로 있는 대형사. 아래 표에 손으로 적었고, 짝이 없는
--       이름은 적지 않았다. "아틀러스" 처럼 영문 줄이 없는 곳은 건드리지 않는다 —
--       짐작으로 합치면 서로 다른 회사를 한 줄로 만들 수 있다.

CREATE TABLE company_merge_tmp (
    dup_id  BIGINT NOT NULL PRIMARY KEY,
    keep_id BIGINT NOT NULL
);

-- (1) 이름이 완전히 같은 줄. 사람이 읽을 수 있는 슬러그를 남기고, 둘 다 생성
--     슬러그면 company- 쪽을 남긴다 — 회사 슬러그가 game- 으로 시작하는 것은
--     그 자체가 잘못이다.
INSERT INTO company_merge_tmp (dup_id, keep_id)
SELECT t.id, t.keep_id
FROM (
    SELECT c.id,
           FIRST_VALUE(c.id) OVER (
               PARTITION BY c.name
               ORDER BY (c.slug REGEXP '^(company|game)-'),
                        (c.slug REGEXP '^game-'),
                        (c.wikidata_id IS NULL),
                        c.id
           ) AS keep_id
    FROM company c
    WHERE c.name IN (SELECT name FROM company GROUP BY name HAVING COUNT(*) > 1)
) t
WHERE t.id <> t.keep_id;

-- (2) 한국어 표기 -> 영문 정본. 양쪽이 다 있을 때만 들어간다.
INSERT IGNORE INTO company_merge_tmp (dup_id, keep_id)
SELECT d.id, k.id
FROM (
             SELECT '닌텐도' AS dup_name, 'nintendo' AS keep_slug
   UNION ALL SELECT '반다이 남코 엔터테인먼트', 'bandai-namco-entertainment'
   UNION ALL SELECT '소니 인터랙티브 엔터테인먼트', 'sony-interactive-entertainment'
   UNION ALL SELECT '스퀘어 에닉스', 'square-enix'
   UNION ALL SELECT '캡콤', 'capcom'
   UNION ALL SELECT '에픽게임즈', 'epic-games'
   -- Focus Home Interactive 는 2022년에 Focus Entertainment 로 이름을 바꿨다. 같은 회사다.
   UNION ALL SELECT '포커스 홈 인터랙티브', 'focus-entertainment'
   UNION ALL SELECT '세가', 'sega'
   UNION ALL SELECT '유비소프트', 'ubisoft'
   UNION ALL SELECT '일렉트로닉 아츠', 'electronic-arts'
   UNION ALL SELECT '블리자드 엔터테인먼트', 'blizzard-entertainment'
   UNION ALL SELECT '베데스다 소프트웍스', 'bethesda-softworks'
   UNION ALL SELECT '베데스다 게임 스튜디오', 'bethesda-game-studios'
   -- Microsoft Game Studios 는 Xbox Game Studios 의 옛 이름이다.
   UNION ALL SELECT '마이크로소프트 게임 스튜디오', 'xbox-game-studios'
   UNION ALL SELECT '디볼버 디지털', 'devolver-digital'
   UNION ALL SELECT '코나미 디지털 엔터테인먼트', 'konami'
   UNION ALL SELECT '코나미', 'konami'
   UNION ALL SELECT '안나푸르나 인터랙티브', 'annapurna-interactive'
   UNION ALL SELECT '레미디 엔터테인먼트', 'remedy-entertainment'
   UNION ALL SELECT '프롬소프트웨어', 'fromsoftware'
   UNION ALL SELECT '록스타 게임즈', 'rockstar-games'
   UNION ALL SELECT '워너 브라더스 인터랙티브 엔터테인먼트', 'warner-bros-games'
   UNION ALL SELECT '인섬니악 게임스', 'insomniac-games'
) m
JOIN company d ON d.name = m.dup_name
JOIN company k ON k.slug = m.keep_slug
WHERE d.id <> k.id;

-- (1)에서 A->B 로 묶인 줄이 (2)에서 B->C 로 다시 묶일 수 있다. 사슬을 편다.
-- 두 번이면 충분하지만 한 번 더 돌려 둔다. 남는 수고가 사슬이 끊기는 것보다 싸다.
UPDATE company_merge_tmp m JOIN company_merge_tmp p ON p.dup_id = m.keep_id SET m.keep_id = p.keep_id;
UPDATE company_merge_tmp m JOIN company_merge_tmp p ON p.dup_id = m.keep_id SET m.keep_id = p.keep_id;
UPDATE company_merge_tmp m JOIN company_merge_tmp p ON p.dup_id = m.keep_id SET m.keep_id = p.keep_id;
DELETE FROM company_merge_tmp WHERE dup_id = keep_id;

-- 게임을 정본 쪽으로 옮기고 빈 줄을 지운다. company 를 가리키는 외래키는
-- game.developer_id 와 game.publisher_id 둘뿐이라 이것으로 끝난다.
UPDATE game g JOIN company_merge_tmp m ON g.developer_id = m.dup_id SET g.developer_id = m.keep_id;
UPDATE game g JOIN company_merge_tmp m ON g.publisher_id = m.dup_id SET g.publisher_id = m.keep_id;
DELETE c FROM company c JOIN company_merge_tmp m ON c.id = m.dup_id;

DROP TABLE company_merge_tmp;
