ALTER TABLE game
  ADD COLUMN anticipation_score DECIMAL(5,2) NOT NULL DEFAULT 75 AFTER discovery_score,
  ADD KEY idx_game_anticipation (anticipation_score DESC);

UPDATE game
SET anticipation_score = LEAST(100, GREATEST(65,
  discovery_score + CASE WHEN status = 'UPCOMING' THEN 2 ELSE -4 END
));

INSERT INTO game (
  slug, original_title, title, tagline, description, developer_id, publisher_id,
  release_date, official_url, steam_app_id, wikidata_id, catalog_source,
  cover_image_url, image_source, release_label, status, discovery_score,
  anticipation_score, follower_count, accent, accent_secondary, symbol, featured
)
SELECT
  'grand-theft-auto-vi', 'Grand Theft Auto VI', 'Grand Theft Auto VI',
  '제이슨과 루시아, 다시 돌아온 바이스 시티',
  '락스타 게임즈가 선보이는 차세대 오픈월드 범죄 액션 게임. 공식 발표 기준 2026년 11월 19일 PlayStation 5와 Xbox Series X|S로 출시됩니다.',
  c.id, c.id, '2026-11-19', 'https://www.rockstargames.com/VI', NULL, NULL, 'ROCKSTAR_OFFICIAL',
  'https://www.rockstargames.com/VI/-/opengraph-image.jpg?opengraph-image.0t8ty~nlmxq2s.jpg',
  'ROCKSTAR_OFFICIAL', '2026. 11. 19 출시 예정', 'UPCOMING', 100, 100, 0,
  '#e9578f', '#512f8e', 'VI', b'1'
FROM company c
WHERE c.slug = 'rockstar-games'
  AND NOT EXISTS (SELECT 1 FROM game WHERE slug = 'grand-theft-auto-vi');

INSERT IGNORE INTO game_genre (game_id, genre)
SELECT id, '오픈월드' FROM game WHERE slug = 'grand-theft-auto-vi';
INSERT IGNORE INTO game_genre (game_id, genre)
SELECT id, '액션' FROM game WHERE slug = 'grand-theft-auto-vi';
INSERT IGNORE INTO game_platform (game_id, platform)
SELECT id, 'PS5' FROM game WHERE slug = 'grand-theft-auto-vi';
INSERT IGNORE INTO game_platform (game_id, platform)
SELECT id, 'Xbox' FROM game WHERE slug = 'grand-theft-auto-vi';
INSERT IGNORE INTO game_release (game_id, platform, release_date, status, region)
SELECT id, 'PS5', '2026-11-19', 'CONFIRMED', 'GLOBAL' FROM game WHERE slug = 'grand-theft-auto-vi';
INSERT IGNORE INTO game_release (game_id, platform, release_date, status, region)
SELECT id, 'Xbox', '2026-11-19', 'CONFIRMED', 'GLOBAL' FROM game WHERE slug = 'grand-theft-auto-vi';

INSERT INTO source (slug, name, type, base_url, collection_method, terms_url, policy_status,
  rate_limit_per_hour, attribution_rule, last_legal_review_at, official, active)
SELECT 'rockstar-newswire', 'Rockstar Games Newswire', 'OFFICIAL_WEB', 'https://www.rockstargames.com/newswire',
  'MANUAL_VERIFIED', 'https://www.rockstargames.com/legal', 'ALLOWED', 10,
  'Rockstar Games 공식 원문 링크 표시', '2026-08-27', b'1', b'1'
WHERE NOT EXISTS (SELECT 1 FROM source WHERE slug = 'rockstar-newswire');

INSERT INTO game_event (game_id, type, title, summary, event_date, published_at)
SELECT g.id, 'ANNOUNCEMENT', 'Grand Theft Auto VI, 11월 19일 출시 확정',
  '락스타 게임즈가 Grand Theft Auto VI의 2026년 11월 19일 출시와 PS5·Xbox Series X|S 지원을 공식 발표했습니다.',
  '2025-11-06', '2025-11-06 12:00:00.000000'
FROM game g
WHERE g.slug = 'grand-theft-auto-vi'
  AND NOT EXISTS (SELECT 1 FROM game_event e WHERE e.game_id = g.id AND e.title = 'Grand Theft Auto VI, 11월 19일 출시 확정');

INSERT IGNORE INTO game_event_source (game_event_id, source_id, source_url, is_official)
SELECT e.id, s.id,
  'https://www.rockstargames.com/kr/newswire/article/ak3ak31a49a221/grand-theft-auto-vi-is-now-set-to-launch-november-19-2026', b'1'
FROM game_event e
JOIN game g ON g.id = e.game_id
JOIN source s ON s.slug = 'rockstar-newswire'
WHERE g.slug = 'grand-theft-auto-vi' AND e.title = 'Grand Theft Auto VI, 11월 19일 출시 확정';
