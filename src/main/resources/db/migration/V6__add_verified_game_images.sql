ALTER TABLE game
    ADD COLUMN cover_image_url VARCHAR(1000) NULL AFTER catalog_source,
    ADD COLUMN image_source VARCHAR(40) NULL AFTER cover_image_url;

-- Rebuild the structured catalog with the stricter verified-source rule.
-- Steam/official games from the curated pipeline are retained.
DELETE FROM game WHERE catalog_source = 'WIKIDATA_CC0';

UPDATE game
SET cover_image_url = CONCAT('https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/', steam_app_id, '/header.jpg'),
    image_source = 'STEAM_CDN'
WHERE steam_app_id IS NOT NULL;
