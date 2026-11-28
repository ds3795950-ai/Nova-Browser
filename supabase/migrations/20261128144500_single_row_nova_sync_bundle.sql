-- Store the encrypted browser snapshot and encrypted password vault together
-- in one nova_sync_state row. Legacy nova_encrypted_vault rows remain read-only
-- compatibility input for older backups and are not written by this RPC.
-- SECURITY INVOKER preserves the caller's RLS policies.
drop function if exists public.nova_upload_sync_bundle(jsonb, jsonb, timestamptz, timestamptz);

create or replace function public.nova_upload_sync_bundle(
    p_bundle jsonb,
    p_expected_updated_at timestamptz
)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_current_updated_at timestamptz;
    v_new_updated_at timestamptz;
begin
    if v_user_id is null then
        raise exception 'AUTH_REQUIRED' using errcode = '42501';
    end if;
    if p_bundle is null
       or p_bundle->>'format' is distinct from 'nova-encrypted-sync-bundle-v1'
       or jsonb_typeof(p_bundle->'browser') is distinct from 'object'
       or jsonb_typeof(p_bundle->'vault') is distinct from 'object' then
        raise exception 'SYNC_BUNDLE_INVALID' using errcode = '22023';
    end if;

    select updated_at
      into v_current_updated_at
      from public.nova_sync_state
     where user_id = v_user_id
     for update;

    if not found then
        if p_expected_updated_at is not null then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
        insert into public.nova_sync_state (user_id, snapshot)
        values (v_user_id, p_bundle)
        on conflict (user_id) do nothing
        returning updated_at into v_new_updated_at;
        if not found then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    else
        if v_current_updated_at is distinct from p_expected_updated_at then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
        update public.nova_sync_state
           set snapshot = p_bundle
         where user_id = v_user_id
           and updated_at = v_current_updated_at
        returning updated_at into v_new_updated_at;
        if not found then
            raise exception 'CLOUD_SYNC_CONFLICT' using errcode = 'P0001';
        end if;
    end if;

    return jsonb_build_object('updated_at', v_new_updated_at);
end;
$$;

revoke all on function public.nova_upload_sync_bundle(jsonb, timestamptz) from public;
revoke all on function public.nova_upload_sync_bundle(jsonb, timestamptz) from anon;
grant execute on function public.nova_upload_sync_bundle(jsonb, timestamptz) to authenticated;

notify pgrst, 'reload schema';
