-- Keep the short-term projection synchronized when a caller repairs or
-- regenerates the denormalized pinyin/initial columns independently of the
-- source title or artist columns.
DROP TRIGGER IF EXISTS trg_songs_search_terms_update ON songs;

CREATE TRIGGER trg_songs_search_terms_update
AFTER UPDATE OF title, artist, title_py, title_init, artist_py, artist_init,
    language, tags ON songs
FOR EACH ROW
WHEN (OLD.title IS DISTINCT FROM NEW.title
   OR OLD.artist IS DISTINCT FROM NEW.artist
   OR OLD.title_py IS DISTINCT FROM NEW.title_py
   OR OLD.title_init IS DISTINCT FROM NEW.title_init
   OR OLD.artist_py IS DISTINCT FROM NEW.artist_py
   OR OLD.artist_init IS DISTINCT FROM NEW.artist_init
   OR OLD.language IS DISTINCT FROM NEW.language
   OR OLD.tags IS DISTINCT FROM NEW.tags)
EXECUTE FUNCTION ktv_sync_song_search_terms();
