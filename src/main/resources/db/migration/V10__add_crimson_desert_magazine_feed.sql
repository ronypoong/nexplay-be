INSERT INTO game (
  slug, original_title, title, tagline, description, developer_id, publisher_id,
  release_date, official_url, steam_app_id, wikidata_id, catalog_source,
  cover_image_url, image_source, release_label, status, discovery_score,
  follower_count, accent, accent_secondary, symbol, featured
)
SELECT
  'crimson-desert-enhanced', 'Crimson Desert', '붉은사막 Enhanced',
  '새로운 이야기와 시스템으로 확장된 붉은사막',
  '펄어비스의 오픈월드 액션 어드벤처. 공식 Steam 뉴스에서 대규모 업데이트, 패치와 향후 확장 콘텐츠 소식을 추적합니다.',
  c.id, c.id, '2026-03-19', 'https://crimsondesert.pearlabyss.com', 3321460, NULL, 'STEAM_STOREFRONT_VERIFIED',
  'https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/3321460/4a895369007efe7cc9da27a999bbca2427d92bb3/header_koreana.jpg?t=1787682679',
  'STEAM_STOREFRONT_API', '출시됨 · 2026. 03. 19', 'AVAILABLE', 99, 0,
  '#8f2f38', '#20111a', '붉은', b'1'
FROM company c
WHERE c.slug = 'pearl-abyss'
  AND NOT EXISTS (SELECT 1 FROM game WHERE steam_app_id = 3321460);

INSERT IGNORE INTO game_genre (game_id, genre)
SELECT id, '액션' FROM game WHERE steam_app_id = 3321460;
INSERT IGNORE INTO game_genre (game_id, genre)
SELECT id, '어드벤처' FROM game WHERE steam_app_id = 3321460;
INSERT IGNORE INTO game_platform (game_id, platform)
SELECT id, 'PC' FROM game WHERE steam_app_id = 3321460;
INSERT IGNORE INTO game_platform (game_id, platform)
SELECT id, 'PS5' FROM game WHERE steam_app_id = 3321460;
INSERT IGNORE INTO game_platform (game_id, platform)
SELECT id, 'Xbox' FROM game WHERE steam_app_id = 3321460;

INSERT IGNORE INTO game_release (game_id, platform, release_date, status, region)
SELECT id, 'PC', '2026-03-19', 'RELEASED', 'GLOBAL' FROM game WHERE steam_app_id = 3321460;

INSERT IGNORE INTO source_subscription (source_id, game_id, external_game_id, feed_url, active)
SELECT s.id, g.id, '3321460', 'https://store.steampowered.com/feeds/news/app/3321460/', b'1'
FROM source s JOIN game g ON g.steam_app_id = 3321460
WHERE s.slug = 'steam-news-rss';
