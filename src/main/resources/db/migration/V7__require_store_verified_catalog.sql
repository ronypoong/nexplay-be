-- Rebuild once more: public discovery now requires a Steam product identifier,
-- which also guarantees a product page and a deterministic cover image URL.
DELETE FROM game WHERE catalog_source = 'WIKIDATA_CC0';
