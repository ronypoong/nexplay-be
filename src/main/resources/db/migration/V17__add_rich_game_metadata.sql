ALTER TABLE game
    ADD COLUMN korean_text_supported BIT NULL AFTER image_source,
    ADD COLUMN korean_audio_supported BIT NULL AFTER korean_text_supported;

CREATE TABLE game_mode (
    game_id BIGINT NOT NULL,
    mode VARCHAR(60) NOT NULL,
    UNIQUE KEY uk_game_mode (game_id, mode),
    KEY idx_game_mode_value (mode),
    CONSTRAINT fk_game_mode_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_language_support (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    language_code VARCHAR(20) NOT NULL,
    language_name VARCHAR(80) NOT NULL,
    text_supported BIT NOT NULL DEFAULT b'0',
    audio_supported BIT NOT NULL DEFAULT b'0',
    source_name VARCHAR(80) NOT NULL,
    source_url VARCHAR(700) NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_language (game_id, language_code),
    CONSTRAINT fk_game_language_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    type VARCHAR(30) NOT NULL,
    external_id VARCHAR(200) NULL,
    title VARCHAR(240) NULL,
    url VARCHAR(1000) NOT NULL,
    thumbnail_url VARCHAR(1000) NULL,
    official BIT NOT NULL DEFAULT b'0',
    source_name VARCHAR(80) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    verified_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_media_external (game_id, type, external_id),
    KEY idx_game_media_game_sort (game_id, sort_order),
    CONSTRAINT fk_game_media_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_relation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    related_game_id BIGINT NULL,
    relation_type VARCHAR(40) NOT NULL,
    external_title VARCHAR(240) NULL,
    external_url VARCHAR(1000) NULL,
    source_name VARCHAR(80) NOT NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_relation (game_id, related_game_id, relation_type),
    CONSTRAINT fk_game_relation_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE,
    CONSTRAINT fk_game_relation_related FOREIGN KEY (related_game_id) REFERENCES game (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE release_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    platform VARCHAR(40) NOT NULL,
    previous_date DATE NULL,
    new_date DATE NULL,
    change_type VARCHAR(40) NOT NULL,
    announced_at DATE NOT NULL,
    source_name VARCHAR(80) NOT NULL,
    source_url VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_release_revision_game (game_id, announced_at DESC),
    CONSTRAINT fk_release_revision_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE popularity_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    snapshot_date DATE NOT NULL,
    discovery_score DECIMAL(5,2) NOT NULL,
    anticipation_score DECIMAL(5,2) NOT NULL,
    follower_count BIGINT NOT NULL DEFAULT 0,
    official_news_30d INT NOT NULL DEFAULT 0,
    trailer_view_count BIGINT NULL,
    source_name VARCHAR(80) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_popularity_snapshot (game_id, snapshot_date, source_name),
    KEY idx_popularity_date (snapshot_date DESC),
    CONSTRAINT fk_popularity_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_requirement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    platform VARCHAR(40) NOT NULL,
    requirement_level VARCHAR(30) NOT NULL,
    os VARCHAR(500) NULL,
    processor VARCHAR(500) NULL,
    memory VARCHAR(200) NULL,
    graphics VARCHAR(500) NULL,
    storage VARCHAR(200) NULL,
    raw_text TEXT NULL,
    source_name VARCHAR(80) NOT NULL,
    source_url VARCHAR(700) NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_requirement (game_id, platform, requirement_level),
    CONSTRAINT fk_system_requirement_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_price_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    store VARCHAR(40) NOT NULL,
    region VARCHAR(20) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    initial_price BIGINT NOT NULL,
    final_price BIGINT NOT NULL,
    discount_percent INT NOT NULL DEFAULT 0,
    store_url VARCHAR(700) NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_price_game_captured (game_id, captured_at DESC),
    CONSTRAINT fk_price_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_age_rating (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    rating_system VARCHAR(30) NOT NULL,
    rating VARCHAR(40) NOT NULL,
    descriptors VARCHAR(500) NULL,
    source_name VARCHAR(80) NOT NULL,
    source_url VARCHAR(700) NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_age_rating (game_id, rating_system),
    CONSTRAINT fk_age_rating_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_accessibility_feature (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    category VARCHAR(40) NOT NULL,
    feature VARCHAR(120) NOT NULL,
    source_name VARCHAR(80) NOT NULL,
    source_url VARCHAR(700) NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_accessibility (game_id, feature),
    CONSTRAINT fk_accessibility_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_data_provenance (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    field_name VARCHAR(80) NOT NULL,
    source_name VARCHAR(80) NOT NULL,
    source_url VARCHAR(700) NULL,
    confidence VARCHAR(20) NOT NULL,
    verified_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_provenance (game_id, field_name, source_name),
    CONSTRAINT fk_provenance_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO game_media (game_id, type, external_id, title, url, thumbnail_url, official, source_name, sort_order, verified_at)
SELECT id, 'TRAILER', 'VQRLujxTm3c', 'Grand Theft Auto VI 공식 트레일러 2',
       'https://www.youtube.com/watch?v=VQRLujxTm3c', 'https://i.ytimg.com/vi/VQRLujxTm3c/hqdefault.jpg',
       b'1', 'Rockstar Games 공식 YouTube', 0, CURRENT_TIMESTAMP(6)
FROM game WHERE slug = 'grand-theft-auto-vi';

INSERT INTO game_media (game_id, type, external_id, title, url, thumbnail_url, official, source_name, sort_order, verified_at)
SELECT id, 'TRAILER', 'VWIw_f8e9Pg', '붉은사막 공식 트레일러',
       'https://www.youtube.com/watch?v=VWIw_f8e9Pg', 'https://i.ytimg.com/vi/VWIw_f8e9Pg/hqdefault.jpg',
       b'1', 'Pearl Abyss 공식 YouTube', 0, CURRENT_TIMESTAMP(6)
FROM game WHERE slug = 'crimson-desert-enhanced';

INSERT INTO release_revision (game_id, platform, previous_date, new_date, change_type, announced_at, source_name, source_url)
SELECT r.game_id, r.platform, NULL, r.release_date, 'INITIAL_CONFIRMATION', r.release_date,
       CASE WHEN g.catalog_source = 'WIKIDATA_CC0' THEN 'Wikidata' ELSE g.catalog_source END,
       g.official_url
FROM game_release r JOIN game g ON g.id = r.game_id;

INSERT INTO popularity_snapshot (
    game_id, snapshot_date, discovery_score, anticipation_score, follower_count,
    official_news_30d, trailer_view_count, source_name
)
SELECT g.id, CURRENT_DATE, g.discovery_score, g.anticipation_score, g.follower_count,
       (SELECT COUNT(*) FROM game_event e WHERE e.game_id = g.id AND e.event_date >= CURRENT_DATE - INTERVAL 30 DAY),
       NULL, 'NEXPLAY'
FROM game g;

INSERT INTO game_data_provenance (game_id, field_name, source_name, source_url, confidence, verified_at)
SELECT id, 'catalog',
       CASE WHEN catalog_source = 'WIKIDATA_CC0' THEN 'Wikidata' ELSE catalog_source END,
       official_url, CASE WHEN official_url IS NOT NULL THEN 'HIGH' ELSE 'MEDIUM' END, CURRENT_TIMESTAMP(6)
FROM game;
