-- Atomically validate and update both original encrypted sync rows.
-- SECURITY INVOKER preserves the caller's RLS policies.
create or replace function public.nova_upload_sync_bundle(
    p_snapshot jsonb,
    p_legacy_vault jsonb,
    p_expected_snapshot_updated_at timestamptz,
    p_expected_legacy_vault_updated_at timestamptz
)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_current_snapshot_updated_at timestamptz;
    v_current_legacy_vault_updated_at timestamptz;
    v_new_snapshot_updated_at timestamptz;
    v_new_legacy_vault_updated_at timestamptz;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = '42501';
    end if;
    if p_snapshot is null or p_legacy_vault is null then
        raise exception 'SYNC_PAYLOAD_REQUIRED' using errcode = '22023';
    end if;

    select updated_at
      into v_current_snapshot_updated_at
      from public.nova_sync_state
     where user_id = v_user_id
     for update;

    if not found then
        if p_expected_snapshot_updated_at is not null then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    elsif v_current_snapshot_updated_at is distinct from p_expected_snapshot_updated_at then
        raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
    end if;

    select updated_at
      into v_current_legacy_vault_updated_at
      from public.nova_encrypted_vault
     where user_id = v_user_id
     for update;

    if not found then
        if p_expected_legacy_vault_updated_at is not null then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    elsif v_current_legacy_vault_updated_at is distinct from p_expected_legacy_vault_updated_at then
        raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
    end if;

    if v_current_snapshot_updated_at is null then
        insert into public.nova_sync_state (user_id, snapshot)
        values (v_user_id, p_snapshot)
        on conflict (user_id) do nothing
        returning updated_at into v_new_snapshot_updated_at;

        if not found then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    else
        update public.nova_sync_state
           set snapshot = p_snapshot
         where user_id = v_user_id
           and updated_at = v_current_snapshot_updated_at
        returning updated_at into v_new_snapshot_updated_at;

        if not found then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    end if;

    if v_current_legacy_vault_updated_at is null then
        insert into public.nova_encrypted_vault (user_id, payload)
        values (v_user_id, p_legacy_vault)
        on conflict (user_id) do nothing
        returning updated_at into v_new_legacy_vault_updated_at;

        if not found then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    else
        update public.nova_encrypted_vault
           set payload = p_legacy_vault
         where user_id = v_user_id
           and updated_at = v_current_legacy_vault_updated_at
        returning updated_at into v_new_legacy_vault_updated_at;

        if not found then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    end if;

    return jsonb_build_object(
        'snapshot_updated_at', v_new_snapshot_updated_at,
        'vault_updated_at', v_new_legacy_vault_updated_at
    );
end;
$$;

revoke all on function public.nova_upload_sync_bundle(jsonb, jsonb, timestamptz, timestamptz) from public;
revoke all on function public.nova_upload_sync_bundle(jsonb, jsonb, timestamptz, timestamptz) from anon;
grant execute on function public.nova_upload_sync_bundle(jsonb, jsonb, timestamptz, timestamptz) to authenticated;
