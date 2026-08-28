-- Wikidata 는 연도만 알 때 출시일을 1월 1일로 내려준다.
-- 카탈로그 동기화가 이걸 무조건 "출시 예정" 으로 넣어서, 2025년에 나온 게임이
-- 지금도 홈의 출시 예정 목록 맨 앞에 앉아 있었다. 상태는 저장 컬럼이라
-- 코드를 고쳐도 이미 들어간 행은 그대로여서 여기서 한 번 맞춘다.

-- 1) 연도만 아는 날짜인데 그 해가 이미 지난 게임
UPDATE game
SET status = 'AVAILABLE',
    release_label = IF(
        release_label = CONCAT(YEAR(release_date), '년 출시 예정'),
        CONCAT(YEAR(release_date), '년 출시'),
        release_label
    )
WHERE status = 'UPCOMING'
  AND release_date IS NOT NULL
  AND MONTH(release_date) = 1
  AND DAY(release_date) = 1
  AND YEAR(release_date) < YEAR(CURDATE());

-- 2) 정확한 날짜가 이미 지났는데 예정으로 남은 게임
UPDATE game
SET status = 'AVAILABLE'
WHERE status = 'UPCOMING'
  AND release_date IS NOT NULL
  AND NOT (MONTH(release_date) = 1 AND DAY(release_date) = 1)
  AND release_date < CURDATE();

-- 3) 플랫폼별 출시 행도 같은 규칙으로
UPDATE game_release
SET status = 'RELEASED'
WHERE status = 'EXPECTED'
  AND (
    (MONTH(release_date) = 1 AND DAY(release_date) = 1 AND YEAR(release_date) < YEAR(CURDATE()))
    OR (NOT (MONTH(release_date) = 1 AND DAY(release_date) = 1) AND release_date < CURDATE())
  );
