-- 사진 없는 게임을 다시 보게 한다.
--
-- 확장 수집이 Steam 에서 대표 이미지를 받아 오면서 저장하지 않고 있었다.
-- 그 게임들은 이미 "확인함" 으로 표시돼 있어 재방문 대상에서도 빠진다.
-- 표시를 지워 다음 수집 때 다시 보게 한다.
DELETE p FROM game_data_provenance p
JOIN game g ON g.id = p.game_id
WHERE p.field_name = 'extended_metadata_checked'
  AND p.source_name = 'Steam Store'
  AND (g.cover_image_url IS NULL OR g.cover_image_url = '');
