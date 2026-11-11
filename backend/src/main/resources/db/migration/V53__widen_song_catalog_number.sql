ALTER TABLE songs
    ALTER COLUMN catalog_number TYPE VARCHAR(10);

COMMENT ON COLUMN songs.catalog_number IS 'Optional 8 to 10 digit catalog number parsed from verified filename metadata';
