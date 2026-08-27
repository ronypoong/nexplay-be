INSERT IGNORE INTO game_genre (game_id, genre)
SELECT game_id,
       CASE LOWER(genre)
           WHEN '경주' THEN '레이싱'
           WHEN 'roguelite' THEN '로그라이트'
           WHEN 'science fiction' THEN 'SF'
           WHEN '공상과학' THEN 'SF'
           ELSE genre
       END
FROM game_genre
WHERE LOWER(genre) IN ('경주', 'roguelite', 'science fiction', '공상과학');

DELETE FROM game_genre
WHERE LOWER(genre) IN ('경주', 'roguelite', 'science fiction', '공상과학');
