ALTER TABLE game
    ADD COLUMN original_title VARCHAR(180) NULL AFTER slug,
    ADD COLUMN official_url VARCHAR(500) NULL AFTER release_date,
    ADD COLUMN steam_app_id BIGINT NULL AFTER official_url,
    ADD UNIQUE KEY uk_game_steam_app_id (steam_app_id);

ALTER TABLE source
    ADD COLUMN type VARCHAR(30) NOT NULL DEFAULT 'OFFICIAL_RSS' AFTER name,
    ADD COLUMN collection_method VARCHAR(20) NOT NULL DEFAULT 'RSS' AFTER base_url,
    ADD COLUMN terms_url VARCHAR(500) NULL AFTER collection_method,
    ADD COLUMN policy_status VARCHAR(30) NOT NULL DEFAULT 'LEGAL_REVIEW' AFTER terms_url,
    ADD COLUMN rate_limit_per_hour INT NOT NULL DEFAULT 60 AFTER policy_status,
    ADD COLUMN attribution_rule VARCHAR(500) NULL AFTER rate_limit_per_hour,
    ADD COLUMN robots_checked_at DATE NULL AFTER attribution_rule,
    ADD COLUMN last_legal_review_at DATE NULL AFTER robots_checked_at,
    ADD COLUMN active BIT NOT NULL DEFAULT b'1' AFTER official;

CREATE TABLE raw_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id BIGINT NOT NULL,
    game_id BIGINT NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    source_url VARCHAR(700) NOT NULL,
    title VARCHAR(500) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    raw_payload LONGTEXT NULL,
    content_hash VARCHAR(64) NOT NULL,
    fetched_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    processing_status VARCHAR(30) NOT NULL,
    processed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_raw_item_source_external (source_id, external_id),
    KEY idx_raw_item_processing (processing_status, fetched_at),
    KEY idx_raw_item_hash (content_hash),
    CONSTRAINT fk_raw_item_source FOREIGN KEY (source_id) REFERENCES source (id),
    CONSTRAINT fk_raw_item_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE game_event_source
    ADD COLUMN raw_item_id BIGINT NULL AFTER source_id,
    ADD COLUMN is_official BIT NOT NULL DEFAULT b'0' AFTER source_url,
    ADD KEY idx_event_source_raw_item (raw_item_id),
    ADD CONSTRAINT fk_event_source_raw_item FOREIGN KEY (raw_item_id) REFERENCES raw_item (id) ON DELETE SET NULL;

CREATE TABLE source_subscription (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id BIGINT NOT NULL,
    game_id BIGINT NOT NULL,
    external_game_id VARCHAR(100) NOT NULL,
    feed_url VARCHAR(700) NOT NULL,
    active BIT NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_source_external (source_id, external_game_id),
    CONSTRAINT fk_subscription_source FOREIGN KEY (source_id) REFERENCES source (id),
    CONSTRAINT fk_subscription_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE collector_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id BIGINT NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    status VARCHAR(30) NOT NULL,
    fetched_count INT NOT NULL DEFAULT 0,
    new_item_count INT NOT NULL DEFAULT 0,
    event_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_collector_run_started (started_at DESC),
    CONSTRAINT fk_collector_run_source FOREIGN KEY (source_id) REFERENCES source (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELETE FROM game_event_source;
DELETE FROM game_event;
DELETE FROM game_release;
DELETE FROM game_genre;
DELETE FROM game_platform;
DELETE FROM game;
DELETE FROM company;
DELETE FROM source;

ALTER TABLE company AUTO_INCREMENT = 1;
ALTER TABLE game AUTO_INCREMENT = 1;
ALTER TABLE source AUTO_INCREMENT = 1;
ALTER TABLE game_event AUTO_INCREMENT = 1;
ALTER TABLE game_release AUTO_INCREMENT = 1;

INSERT INTO company (id, slug, name) VALUES
  (1, 'valve', 'Valve'),
  (2, 'larian-studios', 'Larian Studios'),
  (3, 'hello-games', 'Hello Games'),
  (4, 'arrowhead-game-studios', 'Arrowhead Game Studios'),
  (5, 'sony-interactive-entertainment', 'Sony Interactive Entertainment');

INSERT INTO game (id, slug, original_title, title, tagline, description, developer_id, publisher_id, release_date, official_url, steam_app_id, release_label, status, discovery_score, follower_count, accent, accent_secondary, symbol, featured) VALUES
  (1, 'counter-strike-2', 'Counter-Strike 2', 'Counter-Strike 2', '새로운 시대의 Counter-Strike', 'Valve가 운영하는 경쟁형 전술 FPS. Steam 공식 News RSS에서 업데이트와 e스포츠 소식을 수집합니다.', 1, 1, '2023-09-27', 'https://www.counter-strike.net/cs2', 730, '출시됨 · 2023. 09. 27', 'AVAILABLE', 98, 0, '#d99134', '#39352f', 'CS', b'1'),
  (2, 'dota-2', 'Dota 2', 'Dota 2', '매일 진화하는 전략의 전장', 'Valve의 멀티플레이어 전략 게임. 영웅, 패치와 공식 대회 소식을 Steam 공식 피드에서 추적합니다.', 1, 1, '2013-07-09', 'https://www.dota2.com', 570, '출시됨 · 2013. 07. 09', 'AVAILABLE', 96, 0, '#a93624', '#242120', 'D2', b'0'),
  (3, 'baldurs-gate-3', 'Baldur''s Gate 3', 'Baldur''s Gate 3', '선택이 모험의 모든 것을 바꾼다', 'Larian Studios가 개발한 파티 기반 판타지 RPG. 공식 패치와 커뮤니티 업데이트를 추적합니다.', 2, 2, '2023-08-03', 'https://baldursgate3.game', 1086940, '출시됨 · 2023. 08. 03', 'AVAILABLE', 95, 0, '#8a4d2d', '#d7b36a', 'Ⅲ', b'0'),
  (4, 'no-mans-sky', 'No Man''s Sky', 'No Man''s Sky', '무한한 우주를 탐험하세요', 'Hello Games의 우주 탐험 게임. 무료 대형 업데이트와 Expedition 소식을 공식 Steam 피드에서 수집합니다.', 3, 3, '2016-08-12', 'https://www.nomanssky.com', 275850, '출시됨 · 2016. 08. 12', 'AVAILABLE', 93, 0, '#ef5f41', '#35a7c8', '◭', b'0'),
  (5, 'helldivers-2', 'HELLDIVERS™ 2', 'HELLDIVERS 2', '은하계에 관리 민주주의를 전파하라', 'Arrowhead Game Studios의 협동 슈팅 게임. 공식 업데이트와 Warbond 소식을 Steam 피드에서 추적합니다.', 4, 5, '2024-02-08', 'https://www.playstation.com/games/helldivers-2', 553850, '출시됨 · 2024. 02. 08', 'AVAILABLE', 91, 0, '#e4c637', '#1e2937', '★', b'0');

INSERT INTO game_genre (game_id, genre) VALUES
  (1, 'FPS'), (1, 'Competitive'), (2, 'MOBA'), (2, 'Strategy'),
  (3, 'RPG'), (3, 'Adventure'), (4, 'Open World'), (4, 'Survival'),
  (5, 'Co-op'), (5, 'Shooter');

INSERT INTO game_platform (game_id, platform) VALUES
  (1, 'PC'), (2, 'PC'), (3, 'PC'), (3, 'PS5'), (3, 'Xbox'),
  (4, 'PC'), (4, 'PS5'), (4, 'Xbox'), (4, 'Switch 2'), (5, 'PC'), (5, 'PS5');

INSERT INTO source (id, slug, name, type, base_url, collection_method, terms_url, policy_status, rate_limit_per_hour, attribution_rule, robots_checked_at, last_legal_review_at, official, active) VALUES
  (1, 'steam-news-rss', 'Steam News', 'OFFICIAL_RSS', 'https://store.steampowered.com/feeds/news/app/', 'RSS', 'https://store.steampowered.com/legal/', 'ALLOWED', 60, 'Steam News 원문 링크와 게시 시각 표시', '2026-08-26', '2026-08-26', b'1', b'1');

INSERT INTO source_subscription (source_id, game_id, external_game_id, feed_url, active) VALUES
  (1, 1, '730', 'https://store.steampowered.com/feeds/news/app/730/', b'1'),
  (1, 2, '570', 'https://store.steampowered.com/feeds/news/app/570/', b'1'),
  (1, 3, '1086940', 'https://store.steampowered.com/feeds/news/app/1086940/', b'1'),
  (1, 4, '275850', 'https://store.steampowered.com/feeds/news/app/275850/', b'1'),
  (1, 5, '553850', 'https://store.steampowered.com/feeds/news/app/553850/', b'1');

INSERT INTO game_release (game_id, platform, release_date, status, region) VALUES
  (1, 'PC', '2023-09-27', 'RELEASED', 'GLOBAL'),
  (2, 'PC', '2013-07-09', 'RELEASED', 'GLOBAL'),
  (3, 'PC', '2023-08-03', 'RELEASED', 'GLOBAL'), (3, 'PS5', '2023-09-06', 'RELEASED', 'GLOBAL'),
  (4, 'PC', '2016-08-12', 'RELEASED', 'GLOBAL'), (4, 'PS5', '2020-11-12', 'RELEASED', 'GLOBAL'),
  (5, 'PC', '2024-02-08', 'RELEASED', 'GLOBAL'), (5, 'PS5', '2024-02-08', 'RELEASED', 'GLOBAL');
