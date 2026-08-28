-- supported_languages 각주 파싱 버그가 남긴 흔적을 치운다.
--
-- Steam 은 언어 목록 뒤에 "<br><strong>*</strong>음성이 지원되는 언어" 각주를 붙인다.
-- 이걸 떼지 않고 쉼표로 자르는 바람에 마지막 언어가 통째로 오염됐다.
--   "한국어" -> "한국어음성이 지원되는 언어" (코드 ko 가 아니라 lang-1379779196)
-- 26개 게임이 실제로는 한국어를 지원하는데 미지원으로 집계되고 있었다.
--
-- 이름에서 각주만 떼어내 고치기에는 원래 그 언어에 음성 표시(*)가 있었는지
-- 구분할 수 없다. 파서는 이미 고쳤으니, 오염된 게임을 재수집 대상으로
-- 되돌려 정확한 값을 다시 받는 편이 안전하다.

-- 1) 코드 매핑에 실패한 행이 있는 게임을 재수집 대상으로 되돌린다.
--    (lang-<해시> 는 매핑되지 않은 언어명의 폴백이라, 각주 오염과 미등록 언어명을 함께 잡는다)
DELETE p FROM game_data_provenance p
JOIN (
    SELECT DISTINCT game_id FROM game_language_support WHERE language_code LIKE 'lang-%'
) affected ON affected.game_id = p.game_id
WHERE p.field_name = 'extended_metadata_checked';

-- 2) 폴백 코드로 들어간 행을 지운다. 재수집 시 올바른 코드로 다시 채워진다.
DELETE FROM game_language_support WHERE language_code LIKE 'lang-%';

-- 3) 재수집 전까지 한국어 여부는 "확인 중"(NULL)이 맞다.
--    잘못된 미지원 표시가 화면에 남는 것보다 낫다.
UPDATE game g
SET korean_text_supported = NULL, korean_audio_supported = NULL
WHERE NOT EXISTS (
    SELECT 1 FROM game_data_provenance p
    WHERE p.game_id = g.id AND p.field_name = 'extended_metadata_checked'
);
