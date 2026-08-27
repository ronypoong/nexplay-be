UPDATE source SET rate_limit_per_hour = 4 WHERE slug = 'wikidata-catalog';

UPDATE game
SET status = 'UPCOMING'
WHERE catalog_source = 'WIKIDATA_CC0'
  AND MONTH(release_date) = 1
  AND DAY(release_date) = 1;

UPDATE game_release r
JOIN game g ON g.id = r.game_id
SET r.status = 'EXPECTED'
WHERE g.catalog_source = 'WIKIDATA_CC0'
  AND MONTH(g.release_date) = 1
  AND DAY(g.release_date) = 1;
