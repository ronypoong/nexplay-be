UPDATE collector_run
SET status = 'FAILED',
    finished_at = UTC_TIMESTAMP(6),
    error_message = 'Interrupted during catalog sync; recovered on application restart'
WHERE status = 'RUNNING';
