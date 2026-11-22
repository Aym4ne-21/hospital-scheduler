\set ON_ERROR_STOP on
SET search_path = hospital, public;
CREATE TEMP TABLE replay_edit AS
SELECT id, display_name FROM patient WHERE code='demo15.patient.001';
UPDATE patient SET display_name='Local edit preserved by seed replay' WHERE id IN (SELECT id FROM replay_edit);
CREATE TEMP TABLE replay_snapshot(table_name text PRIMARY KEY, payload jsonb NOT NULL);
DO $$
DECLARE t text; data jsonb;
BEGIN
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname='hospital' ORDER BY tablename LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text), ''[]''::jsonb) FROM hospital.%I r',t) INTO data;
        INSERT INTO replay_snapshot VALUES (t,data);
    END LOOP;
END;
$$;
\ir ../seed/demo.sql
DO $$
DECLARE snapshot record; data jsonb;
BEGIN
    FOR snapshot IN SELECT * FROM replay_snapshot LOOP
        EXECUTE format('SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text), ''[]''::jsonb) FROM hospital.%I r',snapshot.table_name) INTO data;
        IF data IS DISTINCT FROM snapshot.payload THEN
            RAISE EXCEPTION 'ASSERTION FAILED: seed replay changed table %',snapshot.table_name;
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM patient WHERE code='demo15.patient.001' AND display_name='Local edit preserved by seed replay') THEN
        RAISE EXCEPTION 'ASSERTION FAILED: seed overwrote a local edit';
    END IF;
    RAISE NOTICE 'PASS: second load preserved every application row and a user edit.';
END;
$$;
-- Restore only this test's own edit in its disposable database.
UPDATE patient p SET display_name=e.display_name FROM replay_edit e WHERE p.id=e.id;
