-- Rebuild with Steam Storefront metadata. This validates that the app page is
-- live and stores the exact current localized header image URL.
DELETE FROM game WHERE catalog_source = 'WIKIDATA_CC0';
