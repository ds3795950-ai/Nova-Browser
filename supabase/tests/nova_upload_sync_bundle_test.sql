-- Transactional integration test for nova_upload_sync_bundle.
-- Creates a synthetic auth user and sync rows, then rolls the entire test back.
-- Safe to run against a test project or an otherwise empty project.
begin;

insert into auth.users (
    id, aud, role, email, encrypted_password, email_confirmed_at,
    raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
    is_sso_user, is_anonymous
) values (
    '00000000-0000-4000-8000-000000000032',
    'authenticated', 'authenticated', 'nova-sync-rpc-test@example.invalid', '',
    now(), '{}'::jsonb, '{}'::jsonb, now(), now(), false, false
);

create or replace function public.nova_sync_test_fail_snapshot_update()
returns trigger
language plpgsql
as $$
begin
    if current_setting('nova.test_fail_snapshot', true) = 'true' then
        raise exception 'FORCED_SNAPSHOT_FAILURE';
    end if;
    return new;
end;
$$;

create trigger nova_sync_test_force_snapshot_failure
before update on public.nova_sync_state
for each row execute function public.nova_sync_test_fail_snapshot_update();

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000032', true);
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-000000000032","role":"authenticated","aud":"authenticated"}', true);

do $$
declare
    v_user_id uuid := auth.uid();
    v_snapshot_revision timestamptz;
    v_vault_revision timestamptz;
    v_snapshot_before jsonb;
    v_vault_before jsonb;
    v_snapshot_after jsonb;
    v_vault_after jsonb;
    v_snapshot_revision_after timestamptz;
    v_vault_revision_after timestamptz;
    v_result jsonb;
begin
    if v_user_id is null or v_user_id <> '00000000-0000-4000-8000-000000000032'::uuid then
        raise exception 'TEST_AUTH_CLAIM_NOT_APPLIED';
    end if;

    -- First upload inserts both rows and returns both new revisions.
    v_result := public.nova_upload_sync_bundle(
        '{"snapshot":"initial"}'::jsonb,
        '{"vault":"initial"}'::jsonb,
        null,
        null
    );
    select updated_at, snapshot into v_snapshot_revision, v_snapshot_before
      from public.nova_sync_state where user_id = v_user_id;
    select updated_at, payload into v_vault_revision, v_vault_before
      from public.nova_encrypted_vault where user_id = v_user_id;
    if v_snapshot_revision is null or v_vault_revision is null
       or v_result->>'snapshot_updated_at' is null
       or v_result->>'vault_updated_at' is null then
        raise exception 'TEST_INITIAL_INSERT_DID_NOT_RETURN_REVISIONS';
    end if;

    -- A stale snapshot revision must reject the whole write.
    begin
        perform public.nova_upload_sync_bundle(
            '{"snapshot":"stale-snapshot"}'::jsonb,
            '{"vault":"stale-snapshot"}'::jsonb,
            '2000-01-01T00:00:00Z'::timestamptz,
            v_vault_revision
        );
        raise exception 'TEST_STALE_SNAPSHOT_CONFLICT_NOT_RAISED';
    exception when others then
        if sqlerrm <> 'CLOUD_SYNC_CONFLICT' then raise; end if;
    end;

    -- A stale vault revision must also reject the whole write.
    begin
        perform public.nova_upload_sync_bundle(
            '{"snapshot":"stale-vault"}'::jsonb,
            '{"vault":"stale-vault"}'::jsonb,
            v_snapshot_revision,
            '2000-01-01T00:00:00Z'::timestamptz
        );
        raise exception 'TEST_STALE_VAULT_CONFLICT_NOT_RAISED';
    exception when others then
        if sqlerrm <> 'CLOUD_SYNC_CONFLICT' then raise; end if;
    end;

    -- A competing first-upload attempt must conflict instead of overwriting.
    begin
        perform public.nova_upload_sync_bundle(
            '{"snapshot":"duplicate-initial"}'::jsonb,
            '{"vault":"duplicate-initial"}'::jsonb,
            null,
            null
        );
        raise exception 'TEST_COMPETING_INITIAL_INSERT_CONFLICT_NOT_RAISED';
    exception when others then
        if sqlerrm <> 'CLOUD_SYNC_CONFLICT' then raise; end if;
    end;

    -- Force the snapshot write to fail after the vault UPDATE. The RPC subtransaction
    -- must roll back the earlier vault UPDATE as well.
    perform set_config('nova.test_fail_snapshot', 'true', true);
    begin
        perform public.nova_upload_sync_bundle(
            '{"snapshot":"must-rollback"}'::jsonb,
            '{"vault":"must-rollback"}'::jsonb,
            v_snapshot_revision,
            v_vault_revision
        );
        raise exception 'TEST_FORCED_SNAPSHOT_FAILURE_NOT_RAISED';
    exception when others then
        if sqlerrm <> 'FORCED_SNAPSHOT_FAILURE' then raise; end if;
    end;
    perform set_config('nova.test_fail_snapshot', 'false', true);

    select updated_at, snapshot into v_snapshot_revision_after, v_snapshot_after
      from public.nova_sync_state where user_id = v_user_id;
    select updated_at, payload into v_vault_revision_after, v_vault_after
      from public.nova_encrypted_vault where user_id = v_user_id;

    if v_snapshot_revision_after is distinct from v_snapshot_revision
       or v_vault_revision_after is distinct from v_vault_revision
       or v_snapshot_after is distinct from v_snapshot_before
       or v_vault_after is distinct from v_vault_before then
        raise exception 'TEST_PARTIAL_WRITE_WAS_NOT_ROLLED_BACK';
    end if;
end;
$$;

rollback;
