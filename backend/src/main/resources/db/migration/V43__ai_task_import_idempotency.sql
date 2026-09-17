-- Keep concurrent import-record analysis requests idempotent just like song tasks.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY target_type, target_id
               ORDER BY created_at DESC, id DESC
           ) AS row_number
    FROM ai_analysis_tasks
    WHERE target_type = 'IMPORT_RECORD'
      AND target_id IS NOT NULL
      AND status IN ('pending', 'processing', 'review')
)
UPDATE ai_analysis_tasks
SET status = 'failed',
    error_message = 'Superseded by a newer active import task during idempotency migration',
    updated_at = now()
WHERE id IN (SELECT id FROM ranked WHERE row_number > 1);

CREATE UNIQUE INDEX uk_ai_tasks_one_active_import
    ON ai_analysis_tasks(target_type, target_id)
    WHERE target_type = 'IMPORT_RECORD'
      AND target_id IS NOT NULL
      AND status IN ('pending', 'processing', 'review');