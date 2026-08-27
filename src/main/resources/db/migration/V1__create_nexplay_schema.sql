CREATE TABLE company (
    id BIGINT NOT NULL AUTO_INCREMENT,
    slug VARCHAR(120) NOT NULL,
    name VARCHAR(160) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game (
    id BIGINT NOT NULL AUTO_INCREMENT,
    slug VARCHAR(120) NOT NULL,
    title VARCHAR(180) NOT NULL,
    tagline VARCHAR(240) NOT NULL,
    description TEXT NOT NULL,
    developer_id BIGINT NOT NULL,
    publisher_id BIGINT NOT NULL,
    release_date DATE NULL,
    release_label VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL,
    discovery_score DECIMAL(5,2) NOT NULL,
    follower_count BIGINT NOT NULL,
    accent VARCHAR(20) NOT NULL,
    accent_secondary VARCHAR(20) NOT NULL,
    symbol VARCHAR(12) NOT NULL,
    featured BIT NOT NULL DEFAULT b'0',
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_slug (slug),
    KEY idx_game_score (discovery_score DESC),
    CONSTRAINT fk_game_developer FOREIGN KEY (developer_id) REFERENCES company (id),
    CONSTRAINT fk_game_publisher FOREIGN KEY (publisher_id) REFERENCES company (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_genre (
    game_id BIGINT NOT NULL,
    genre VARCHAR(60) NOT NULL,
    UNIQUE KEY uk_game_genre (game_id, genre),
    KEY idx_game_genre_value (genre),
    CONSTRAINT fk_game_genre_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_platform (
    game_id BIGINT NOT NULL,
    platform VARCHAR(40) NOT NULL,
    UNIQUE KEY uk_game_platform (game_id, platform),
    KEY idx_game_platform_value (platform),
    CONSTRAINT fk_game_platform_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE source (
    id BIGINT NOT NULL AUTO_INCREMENT,
    slug VARCHAR(120) NOT NULL,
    name VARCHAR(160) NOT NULL,
    base_url VARCHAR(500) NULL,
    official BIT NOT NULL DEFAULT b'0',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(240) NOT NULL,
    summary TEXT NOT NULL,
    event_date DATE NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_game_event_feed (published_at DESC),
    KEY idx_game_event_game_date (game_id, event_date DESC),
    CONSTRAINT fk_game_event_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_event_source (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_event_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    source_url VARCHAR(700) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_source (game_event_id, source_id),
    CONSTRAINT fk_event_source_event FOREIGN KEY (game_event_id) REFERENCES game_event (id) ON DELETE CASCADE,
    CONSTRAINT fk_event_source_source FOREIGN KEY (source_id) REFERENCES source (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE game_release (
    id BIGINT NOT NULL AUTO_INCREMENT,
    game_id BIGINT NOT NULL,
    platform VARCHAR(40) NOT NULL,
    release_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    region VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_game_platform_region (game_id, platform, region),
    KEY idx_release_calendar (release_date, platform),
    CONSTRAINT fk_release_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
