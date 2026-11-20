-- Keep a single monotonic timestamp trigger for compare-and-swap cloud sync.
-- Duplicate triggers using now() could overwrite the monotonic timestamp
-- assigned by nova_touch_updated_at() during the same UPDATE statement.

drop trigger if exists nova_sync_updated_at on public.nova_sync_state;
drop trigger if exists nova_sync_state_updated_at on public.nova_sync_state;
