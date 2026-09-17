ALTER TABLE songs
    ADD COLUMN IF NOT EXISTS catalog_number VARCHAR(8);

UPDATE songs
SET catalog_number = ''
WHERE catalog_number IS NULL;

ALTER TABLE songs
    ALTER COLUMN catalog_number SET DEFAULT '';

COMMENT ON COLUMN songs.catalog_number IS 'Optional fixed-width eight-digit catalog number parsed from filename';
