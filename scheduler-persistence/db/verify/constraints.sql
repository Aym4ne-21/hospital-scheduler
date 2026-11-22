\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path = hospital, public;
SET LOCAL TIME ZONE 'Europe/Paris';

CREATE FUNCTION pg_temp.assert_true(ok boolean, label text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'ASSERTION FAILED: %', label; END IF;
END;
$$;

CREATE FUNCTION pg_temp.expect_error(statement text, expected_state text, expected_constraint text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE actual_state text; actual_constraint text; actual_message text;
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE,
            actual_constraint = CONSTRAINT_NAME, actual_message = MESSAGE_TEXT;
        IF actual_state <> expected_state OR
           (expected_constraint IS NOT NULL AND actual_constraint IS DISTINCT FROM expected_constraint) THEN
            RAISE EXCEPTION 'ASSERTION FAILED: expected % / %, got % / %: %',
                expected_state, expected_constraint, actual_state, actual_constraint, actual_message;
        END IF;
        RETURN;
    END;
    RAISE EXCEPTION 'ASSERTION FAILED: statement succeeded, expected % / %: %',
        expected_state, expected_constraint, statement;
END;
$$;

SELECT pg_temp.assert_true(EXISTS (SELECT 1 FROM pg_extension WHERE extname='btree_gist'), 'btree_gist installed');
SELECT pg_temp.assert_true((SELECT count(*)=2 FROM pg_constraint
    WHERE conrelid='hospital.appointment'::regclass AND contype='x'
      AND conname IN ('confirmed_doctor_no_overlap','confirmed_room_no_overlap')
      AND pg_get_constraintdef(oid) LIKE '%WHERE%CONFIRMED%'), 'both partial exclusions installed');
SELECT pg_temp.assert_true((SELECT count(*)=3 FROM public.database_schema_migration), 'three journalled migrations');

DO $tests$
DECLARE
    h bigint; dep bigint; sp bigint; rt bigint; p bigint; d1 bigint; d2 bigint;
    r1 bigint; r2 bigint; r3 bigint; alg bigint; w bigint; s1 bigint; s2 bigint;
    q bigint[]; qid bigint; i integer;
    insert_appt constant text := 'INSERT INTO hospital.appointment
        (code,schedule_id,request_id,doctor_id,room_id,starts_at,ends_at,status)
        VALUES (%L,%s,%s,%s,%s,%L,%L,%L)';
BEGIN
    INSERT INTO hospital(code,name,time_zone) VALUES ('verify.h','Verification','Europe/Paris') RETURNING id INTO h;
    INSERT INTO department(code,hospital_id,name) VALUES ('verify.dep',h,'Verification') RETURNING id INTO dep;
    INSERT INTO specialty(code,department_id,name) VALUES ('verify.sp',dep,'Verification') RETURNING id INTO sp;
    INSERT INTO room_type(code,name) VALUES ('verify.type','Verification') RETURNING id INTO rt;
    INSERT INTO doctor(code,department_id,display_name) VALUES ('verify.d1',dep,'Fictional A') RETURNING id INTO d1;
    INSERT INTO doctor(code,department_id,display_name) VALUES ('verify.d2',dep,'Fictional B') RETURNING id INTO d2;
    INSERT INTO room(code,department_id,room_type_id,name) VALUES ('verify.r1',dep,rt,'A') RETURNING id INTO r1;
    INSERT INTO room(code,department_id,room_type_id,name) VALUES ('verify.r2',dep,rt,'B') RETURNING id INTO r2;
    INSERT INTO room(code,department_id,room_type_id,name) VALUES ('verify.r3',dep,rt,'C') RETURNING id INTO r3;
    INSERT INTO patient(code,display_name) VALUES ('verify.p','Fictional Patient') RETURNING id INTO p;
    INSERT INTO algorithm(code,name,version) VALUES ('verify.alg','Verification','v1') RETURNING id INTO alg;
    INSERT INTO schedule_week(code,hospital_id,starts_on) VALUES ('verify.week',h,date '2026-11-16') RETURNING id INTO w;
    INSERT INTO schedule(code,schedule_week_id,algorithm_id,random_seed,status)
        VALUES ('verify.s1',w,alg,1,'DRAFT') RETURNING id INTO s1;
    INSERT INTO schedule(code,schedule_week_id,algorithm_id,random_seed,status)
        VALUES ('verify.s2',w,alg,2,'DRAFT') RETURNING id INTO s2;
    PERFORM pg_temp.assert_true((SELECT final_fitness IS NULL FROM schedule WHERE id=s1), 'intermediate fitness is NULL');
    FOR i IN 1..12 LOOP
        INSERT INTO appointment_request(code,patient_id,specialty_id,duration_minutes,urgency,requested_at)
            VALUES ('verify.q.'||i,p,sp,30,'ROUTINE',timestamptz '2026-11-12 09:00+01') RETURNING id INTO qid;
        q := array_append(q,qid);
    END LOOP;

    -- Day 01: exercise the request duration check itself, independently of
    -- appointment bounds. Verify the exact constraint to avoid false positives.
    PERFORM pg_temp.expect_error(format('UPDATE hospital.appointment_request SET duration_minutes=-15 WHERE id=%s',q[1]),
        '23514','appointment_request_duration_minutes_check');
    PERFORM pg_temp.expect_error(format('UPDATE hospital.appointment_request SET duration_minutes=0 WHERE id=%s',q[1]),
        '23514','appointment_request_duration_minutes_check');
    PERFORM pg_temp.expect_error(format('UPDATE hospital.appointment_request SET duration_minutes=1441 WHERE id=%s',q[1]),
        '23514','appointment_request_duration_minutes_check');
    PERFORM pg_temp.expect_error(format('UPDATE hospital.appointment_request SET patient_id=-999 WHERE id=%s',q[1]),
        '23503','appointment_request_patient_id_fkey');
    PERFORM pg_temp.assert_true((SELECT duration_minutes=30 AND patient_id=p
        FROM appointment_request WHERE id=q[1]), 'failed request edits leave the row unchanged');

    -- A real reservation, its exactly adjacent successor, and a separate pair.
    EXECUTE format(insert_appt,'verify.first',s1,q[1],d1,r1,'2026-11-16 10:00+01','2026-11-16 10:30+01','CONFIRMED');
    EXECUTE format(insert_appt,'verify.adjacent',s1,q[2],d1,r1,'2026-11-16 10:30+01','2026-11-16 11:00+01','CONFIRMED');
    -- Same doctor/different room, across schedules: only doctor exclusion can fire.
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.bad-doctor',s2,q[4],d1,r2,
        '2026-11-16 10:15+01','2026-11-16 10:45+01','CONFIRMED'), '23P01','confirmed_doctor_no_overlap');
    -- Same room/different doctor, across schedules: only room exclusion can fire.
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.bad-room',s2,q[5],d2,r1,
        '2026-11-16 10:15+01','2026-11-16 10:45+01','CONFIRMED'), '23P01','confirmed_room_no_overlap');
    EXECUTE format(insert_appt,'verify.independent',s2,q[6],d2,r2,'2026-11-16 10:00+01','2026-11-16 10:30+01','CONFIRMED');

    -- The same request can occur in both candidates and overlap a real booking.
    EXECUTE format(insert_appt,'verify.candidate1',s1,q[3],d1,r3,'2026-11-16 10:10+01','2026-11-16 10:20+01','CANDIDATE');
    EXECUTE format(insert_appt,'verify.candidate2',s2,q[3],d1,r3,'2026-11-16 10:10+01','2026-11-16 10:20+01','CANDIDATE');
    PERFORM pg_temp.expect_error('UPDATE hospital.appointment SET status=''CONFIRMED'' WHERE code=''verify.candidate1''',
        '23P01','confirmed_doctor_no_overlap');
    PERFORM pg_temp.assert_true((SELECT status='CANDIDATE' FROM appointment WHERE code='verify.candidate1'), 'failed UPDATE rolled back');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.duplicate-request',s1,q[1],d2,r3,
        '2026-11-16 14:00+01','2026-11-16 14:30+01','CANDIDATE'), '23505','appointment_request_per_schedule');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.zero-length',s1,q[7],d1,r3,
        '2026-11-16 14:00+01','2026-11-16 14:00+01','CANDIDATE'), '23514','appointment_bounds');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.reversed',s1,q[7],d1,r3,
        '2026-11-16 14:30+01','2026-11-16 14:00+01','CANDIDATE'), '23514','appointment_bounds');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.partial-bounds',s1,q[7],d1,r3,
        '2026-11-16 14:00+01',NULL,'CANDIDATE'), '23514','appointment_bounds');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.infinite',s1,q[7],d1,r3,
        '2026-11-16 14:00+01','infinity','CANDIDATE'), '23514','appointment_bounds');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.missing-doctor',s1,q[7],'NULL',r3,
        '2026-11-16 14:00+01','2026-11-16 14:30+01','CONFIRMED'), '23514','appointment_confirmed_complete');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.missing-room',s1,q[7],d1,'NULL',
        '2026-11-16 14:00+01','2026-11-16 14:30+01','CONFIRMED'), '23514','appointment_confirmed_complete');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.missing-bounds',s1,q[7],d1,r3,
        NULL,NULL,'CONFIRMED'), '23514','appointment_confirmed_complete');
    EXECUTE format(insert_appt,'verify.unassigned',s1,q[8],'NULL','NULL',NULL,NULL,'CANDIDATE');
    PERFORM pg_temp.expect_error('UPDATE hospital.appointment SET locked=true WHERE code=''verify.unassigned''',
        '23514','appointment_lock_confirmed');
    PERFORM pg_temp.expect_error(format(insert_appt,'verify.bad-fk',s1,q[9],-999,r3,
        '2026-11-16 14:00+01','2026-11-16 14:30+01','CANDIDATE'), '23503','appointment_doctor_id_fkey');
    UPDATE schedule SET status='PUBLISHED', final_fitness=0 WHERE id=s1;
    PERFORM pg_temp.expect_error(format('UPDATE hospital.schedule SET status=''PUBLISHED'',final_fitness=0 WHERE id=%s',s2),
        '23505','one_published_schedule_per_week');
    RAISE NOTICE 'PASS: request duration and FKs, adjacency, separate resources, both 23P01 names across schedules, candidates, UPDATE, NULLs, bounds, uniqueness and intermediate fitness.';
END;
$tests$;
ROLLBACK;
