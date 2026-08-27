INSERT IGNORE INTO game_genre (game_id, genre)
SELECT game_id,
       CASE LOWER(genre)
           WHEN 'action' THEN '액션'
           WHEN 'adventure' THEN '어드벤처'
           WHEN 'co-op' THEN '협동'
           WHEN 'competitive' THEN '경쟁'
           WHEN 'open world' THEN '오픈 월드'
           WHEN 'shooter' THEN '슈팅'
           WHEN 'strategy' THEN '전략'
           WHEN 'survival' THEN '생존'
           ELSE genre
       END
FROM game_genre
WHERE LOWER(genre) IN ('action', 'adventure', 'co-op', 'competitive', 'open world', 'shooter', 'strategy', 'survival');

DELETE FROM game_genre
WHERE LOWER(genre) IN ('action', 'adventure', 'co-op', 'competitive', 'open world', 'shooter', 'strategy', 'survival');
