-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- storage.local.base.url held a path where an origin belongs.
--
-- It was seeded as "/media", and StorageService appends "/media/<key>" to it — so every locally-stored file
-- resolved to "/media/media/…" and every photograph on the first listing was a broken image.
--
-- The path is the application's own: MediaController owns /media/**. What the key configures is the host in
-- front of it, and empty means this one — a relative URL, which works behind any host and cannot point at the
-- wrong environment.
--
-- Only the value that is wrong is touched: an environment that has already set a real CDN origin keeps it.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

UPDATE configurations
SET config_value = '',
    description  = 'Host that serves locally-stored files. Empty means this application, which is usually right.',
    label        = 'Local media origin'
WHERE config_key = 'storage.local.base.url'
  AND coalesce(config_value, '') IN ('/media', '/media/');
