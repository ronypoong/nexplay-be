-- 게임 소개가 전부 똑같았다.
--
-- 458개 게임의 description 이 모두 150자 미만이고, 그중 448개는 연도만 다른
-- 같은 문장이었다.
--   "Wikidata CC0 구조화 데이터에서 확인한 2026년 게임입니다. ..."
--
-- Steam appdetails 는 이미 한국어 소개문을 주고 있었는데(about_the_game 500~950자)
-- 파서가 읽지 않고 버리고 있었다. 클라이언트를 고쳤으니 다시 받아 채운다.
--
-- extended_metadata_checked 표시가 있으면 재수집 대상에서 빠지므로,
-- 보일러플레이트 소개를 가진 게임만 표시를 지워 다시 훑게 한다.
DELETE p FROM game_data_provenance p
JOIN game g ON g.id = p.game_id
WHERE p.field_name = 'extended_metadata_checked'
  AND g.steam_app_id IS NOT NULL
  AND (
        g.description LIKE '%Wikidata CC0 구조화 데이터에서 확인한%'
     OR g.description LIKE '%에서 확인한 정보입니다%'
     OR CHAR_LENGTH(g.description) < 150
  );
