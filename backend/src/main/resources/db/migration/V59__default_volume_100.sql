ALTER TABLE player_state
    ALTER COLUMN volume SET DEFAULT 100;

UPDATE player_state
SET volume = 100
WHERE id = 1
  AND volume = 60;
