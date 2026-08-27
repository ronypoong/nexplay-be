ALTER TABLE company
    ADD COLUMN type VARCHAR(30) NOT NULL DEFAULT 'MIXED' AFTER name,
    ADD COLUMN country VARCHAR(80) NULL AFTER type,
    ADD COLUMN official_url VARCHAR(500) NULL AFTER country,
    ADD COLUMN wikidata_id VARCHAR(30) NULL AFTER official_url,
    ADD COLUMN major BIT NOT NULL DEFAULT b'0' AFTER wikidata_id,
    ADD UNIQUE KEY uk_company_wikidata_id (wikidata_id);

ALTER TABLE game
    ADD COLUMN wikidata_id VARCHAR(30) NULL AFTER steam_app_id,
    ADD COLUMN catalog_source VARCHAR(40) NOT NULL DEFAULT 'MANUAL' AFTER wikidata_id,
    ADD UNIQUE KEY uk_game_wikidata_id (wikidata_id);

UPDATE game SET discovery_score = 42, featured = b'0' WHERE id IN (1, 2, 3, 4, 5);

INSERT INTO source (
    id, slug, name, type, base_url, collection_method, terms_url, policy_status,
    rate_limit_per_hour, attribution_rule, robots_checked_at, last_legal_review_at, official, active
) VALUES (
    2, 'wikidata-catalog', 'Wikidata Game Catalog', 'STRUCTURED_DATA_API',
    'https://query.wikidata.org/sparql', 'API', 'https://www.wikidata.org/wiki/Wikidata:Licensing',
    'ALLOWED', 1, 'Wikidata structured data (CC0); retain entity identifier and source name',
    NULL, '2026-08-26', b'0', b'1'
);

INSERT INTO company (slug, name, type, country, official_url, major) VALUES
  ('nintendo', 'Nintendo', 'MIXED', 'Japan', 'https://www.nintendo.com', b'1'),
  ('microsoft-gaming', 'Microsoft Gaming', 'PUBLISHER', 'United States', 'https://www.xbox.com', b'1'),
  ('xbox-game-studios', 'Xbox Game Studios', 'PUBLISHER', 'United States', 'https://www.xbox.com/xbox-game-studios', b'1'),
  ('electronic-arts', 'Electronic Arts', 'MIXED', 'United States', 'https://www.ea.com', b'1'),
  ('ubisoft', 'Ubisoft', 'MIXED', 'France', 'https://www.ubisoft.com', b'1'),
  ('activision', 'Activision', 'PUBLISHER', 'United States', 'https://www.activision.com', b'1'),
  ('blizzard-entertainment', 'Blizzard Entertainment', 'MIXED', 'United States', 'https://www.blizzard.com', b'1'),
  ('rockstar-games', 'Rockstar Games', 'MIXED', 'United States', 'https://www.rockstargames.com', b'1'),
  ('take-two-interactive', 'Take-Two Interactive', 'PUBLISHER', 'United States', 'https://www.take2games.com', b'1'),
  ('capcom', 'Capcom', 'MIXED', 'Japan', 'https://www.capcom.com', b'1'),
  ('bandai-namco-entertainment', 'Bandai Namco Entertainment', 'MIXED', 'Japan', 'https://www.bandainamcoent.com', b'1'),
  ('sega', 'Sega', 'MIXED', 'Japan', 'https://www.sega.com', b'1'),
  ('square-enix', 'Square Enix', 'MIXED', 'Japan', 'https://www.square-enix.com', b'1'),
  ('koei-tecmo', 'Koei Tecmo', 'MIXED', 'Japan', 'https://www.koeitecmo.co.jp', b'1'),
  ('konami', 'Konami Digital Entertainment', 'MIXED', 'Japan', 'https://www.konami.com/games', b'1'),
  ('cd-projekt-red', 'CD Projekt Red', 'DEVELOPER', 'Poland', 'https://www.cdprojektred.com', b'1'),
  ('fromsoftware', 'FromSoftware', 'DEVELOPER', 'Japan', 'https://www.fromsoftware.jp', b'1'),
  ('epic-games', 'Epic Games', 'MIXED', 'United States', 'https://www.epicgames.com', b'1'),
  ('riot-games', 'Riot Games', 'MIXED', 'United States', 'https://www.riotgames.com', b'1'),
  ('krafton', 'Krafton', 'MIXED', 'South Korea', 'https://www.krafton.com', b'1'),
  ('nexon', 'Nexon', 'MIXED', 'South Korea', 'https://www.nexon.com', b'1'),
  ('netmarble', 'Netmarble', 'MIXED', 'South Korea', 'https://www.netmarble.com', b'1'),
  ('ncsoft', 'NCSOFT', 'MIXED', 'South Korea', 'https://www.ncsoft.com', b'1'),
  ('pearl-abyss', 'Pearl Abyss', 'MIXED', 'South Korea', 'https://www.pearlabyss.com', b'1'),
  ('shift-up', 'Shift Up', 'MIXED', 'South Korea', 'https://shiftup.co.kr', b'1'),
  ('tencent-games', 'Tencent Games', 'MIXED', 'China', 'https://game.qq.com', b'1'),
  ('supercell', 'Supercell', 'DEVELOPER', 'Finland', 'https://supercell.com', b'1'),
  ('remedy-entertainment', 'Remedy Entertainment', 'DEVELOPER', 'Finland', 'https://www.remedygames.com', b'1'),
  ('bethesda-game-studios', 'Bethesda Game Studios', 'DEVELOPER', 'United States', 'https://bethesdagamestudios.com', b'1'),
  ('bethesda-softworks', 'Bethesda Softworks', 'PUBLISHER', 'United States', 'https://bethesda.net', b'1'),
  ('warner-bros-games', 'Warner Bros. Games', 'PUBLISHER', 'United States', 'https://warnerbrosgames.com', b'1'),
  ('2k-games', '2K Games', 'PUBLISHER', 'United States', 'https://2k.com', b'1'),
  ('devolver-digital', 'Devolver Digital', 'PUBLISHER', 'United States', 'https://www.devolverdigital.com', b'1'),
  ('annapurna-interactive', 'Annapurna Interactive', 'PUBLISHER', 'United States', 'https://annapurnainteractive.com', b'1'),
  ('team17', 'Team17', 'PUBLISHER', 'United Kingdom', 'https://www.team17.com', b'1'),
  ('independent-unknown', 'Independent / Unknown', 'UNKNOWN', NULL, NULL, b'0')
ON DUPLICATE KEY UPDATE major = VALUES(major);

UPDATE company SET type = 'MIXED', country = 'United States', official_url = 'https://www.valvesoftware.com', major = b'1' WHERE slug = 'valve';
UPDATE company SET type = 'DEVELOPER', country = 'Belgium', official_url = 'https://larian.com', major = b'1' WHERE slug = 'larian-studios';
UPDATE company SET type = 'DEVELOPER', country = 'United Kingdom', official_url = 'https://hellogames.org', major = b'1' WHERE slug = 'hello-games';
UPDATE company SET type = 'DEVELOPER', country = 'Sweden', official_url = 'https://www.arrowheadgamestudios.com', major = b'1' WHERE slug = 'arrowhead-game-studios';
UPDATE company SET type = 'PUBLISHER', country = 'United States', official_url = 'https://sonyinteractive.com', major = b'1' WHERE slug = 'sony-interactive-entertainment';
