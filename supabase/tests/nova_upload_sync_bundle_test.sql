-- Transactional integration test for nova_upload_sync_bundle(p_bundle, p_expected_updated_at).
-- Creates a synthetic auth user and a sync row, then rolls the entire test back.
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
    v_revision timestamptz;
    v_revision_after timestamptz;
    v_before jsonb;
    v_after jsonb;
    v_result jsonb;
    v_legacy_rows integer;
begin
    if v_user_id is null or v_user_id <> '00000000-0000-4000-8000-000000000032'::uuid then
        raise exception 'TEST_AUTH_CLAIM_NOT_APPLIED';
    end if;

    -- First upload inserts one row holding both bundled objects and returns one revision.
    v_result := public.nova_upload_sync_bundle(
        '{"format":"nova-encrypted-sync-bundle-v1","browser":{"payload":"initial"},"vault":{"payload":"initial"}}'::jsonb,
        null
    );
    select updated_at, snapshot into v_revision, v_before
      from public.nova_sync_state where user_id = v_user_id;
    if v_revision is null
       or v_result->>'updated_at' is null
       or v_before->'browser'->>'payload' is distinct from 'initial'
       or v_before->'vault'->>'payload' is distinct from 'initial' then
        raise exception 'TEST_INITIAL_INSERT_DID_NOT_STORE_BUNDLE';
    end if;

    -- The legacy two-table vault must not be written by the new RPC.
    select count(*) into v_legacy_rows
      from public.nova_encrypted_vault where user_id = v_user_id;
    if v_legacy_rows <> 0 then
        raise exception 'TEST_LEGACY_VAULT_TABLE_WAS_WRITTEN';
    end if;

    -- A malformed bundle (wrong format) must be rejected.
    begin
        perform public.nova_upload_sync_bundle(
            '{"format":"wrong","browser":{},"vault":{}}'::jsonb,
            v_revision
        );
        raise exception 'TEST_INVALID_FORMAT_NOT_REJECTED';
    exception when others then
        if sqlerrm <> 'SYNC_BUNDLE_INVALID' then raise; end if;
    end;

    -- A bundle missing its vault object must be rejected.
    begin
        perform public.nova_upload_sync_bundle(
            '{"format":"nova-encrypted-sync-bundle-v1","browser":{}}'::jsonb,
            v_revision
        );
        raise exception 'TEST_MISSING_VAULT_NOT_REJECTED';
    exception when others then
        if sqlerrm <> 'SYNC_BUNDLE_INVALID' then raise; end if;
    end;

    -- A stale revision must reject the whole write.
    begin
        perform public.nova_upload_sync_bundle(
            '{"format":"nova-encrypted-sync-bundle-v1","browser":{"payload":"stale"},"vault":{"payload":"stale"}}'::jsonb,
            '2000-01-01T00:00:00Z'::timestamptz
        );
        raise exception 'TEST_STALE_REVISION_CONFLICT_NOT_RAISED';
    exception when others then
        if sqlerrm <> 'CLOUD_SYNC_CONFLICT' then raise; end if;
    end;

    -- A competing first-upload attempt (no expected revision) must conflict instead of overwriting.
    begin
        perform public.nova_upload_sync_bundle(
            '{"format":"nova-encrypted-sync-bundle-v1","browser":{"payload":"duplicate"},"vault":{"payload":"duplicate"}}'::jsonb,
            null
        );
        raise exception 'TEST_COMPETING_INITIAL_INSERT_CONFLICT_NOT_RAISED';
    exception when others then
        if sqlerrm <> 'CLOUD_SYNC_CONFLICT' then raise; end if;
    end;

    -- Rejected writes must leave both bundled objects unchanged.
    select updated_at, snapshot into v_revision_after, v_after
      from public.nova_sync_state where user_id = v_user_id;
    if v_revision_after is distinct from v_revision
       or v_after is distinct from v_before then
        raise exception 'TEST_REJECTED_WRITE_CHANGED_BUNDLE';
    end if;

    -- A write that fails mid-update must roll back, leaving both bundled objects unchanged.
    perform set_config('nova.test_fail_snapshot', 'true', true);
    begin
        perform public.nova_upload_sync_bundle(
            '{"format":"nova-encrypted-sync-bundle-v1","browser":{"payload":"must-rollback"},"vault":{"payload":"must-rollback"}}'::jsonb,
            v_revision
        );
        raise exception 'TEST_FORCED_FAILURE_NOT_RAISED';
    exception when others then
        if sqlerrm <> 'FORCED_SNAPSHOT_FAILURE' then raise; end if;
    end;
    perform set_config('nova.test_fail_snapshot', 'false', true);

    select updated_at, snapshot into v_revision_after, v_after
      from public.nova_sync_state where user_id = v_user_id;
    if v_revision_after is distinct from v_revision
       or v_after is distinct from v_before then
        raise exception 'TEST_PARTIAL_WRITE_WAS_NOT_ROLLED_BACK';
    end if;

    -- A valid update with the current revision stores both new bundled objects.
    v_result := public.nova_upload_sync_bundle(
        '{"format":"nova-encrypted-sync-bundle-v1","browser":{"payload":"updated"},"vault":{"payload":"updated"}}'::jsonb,
        v_revision
    );
    select snapshot into v_after
      from public.nova_sync_state where user_id = v_user_id;
    if v_result->>'updated_at' is null
       or v_after->'browser'->>'payload' is distinct from 'updated'
       or v_after->'vault'->>'payload' is distinct from 'updated' then
        raise exception 'TEST_VALID_UPDATE_DID_NOT_STORE_BUNDLE';
    end if;
end;
$$;

rollback;
