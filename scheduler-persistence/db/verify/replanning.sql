\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path = hospital, public;
CREATE TEMP TABLE original_locks AS
SELECT id,request_id,doctor_id,room_id,starts_at,ends_at,status,locked FROM appointment WHERE locked;

DO $replan$
DECLARE old_id bigint; new_id bigint; affected bigint;
BEGIN
    -- Serialize publication for the week, then lock both schedules and originals.
    PERFORM 1 FROM schedule_week WHERE code='demo15.week' FOR UPDATE;
    SELECT id INTO STRICT old_id FROM schedule WHERE code='demo15.schedule.published' FOR UPDATE;
    SELECT id INTO STRICT new_id FROM schedule WHERE code='demo15.schedule.candidate-a' FOR UPDATE;
    PERFORM 1 FROM appointment WHERE schedule_id IN (old_id,new_id) ORDER BY id FOR UPDATE;

    INSERT INTO doctor_absence(doctor_id,starts_at,ends_at,reason)
    SELECT id,timestamptz '2026-11-16 10:00+01',timestamptz '2026-11-16 10:30+01','Verification: hypothetical new absence'
    FROM doctor WHERE code='demo15.doc.1';
    SELECT count(*) INTO affected FROM appointment a JOIN doctor_absence x
        ON x.doctor_id=a.doctor_id AND x.occupied_at && a.occupied_at
        WHERE a.status='CONFIRMED';
    IF affected<>1 THEN RAISE EXCEPTION 'ASSERTION FAILED: absence should affect exactly request 011'; END IF;

    UPDATE schedule SET status='ARCHIVED' WHERE id=old_id;
    UPDATE appointment SET status='CANCELLED' WHERE schedule_id=old_id AND NOT locked;
    -- Remove five candidate copies, then transfer the original locked reservations
    -- without changing their IDs, resources, intervals, status or lock flags.
    DELETE FROM appointment c USING appointment original
        WHERE c.schedule_id=new_id AND c.carried_from_appointment_id=original.id
          AND original.schedule_id=old_id AND original.locked;
    UPDATE appointment SET schedule_id=new_id WHERE schedule_id=old_id AND locked;
    UPDATE appointment SET doctor_id=(SELECT id FROM doctor WHERE code='demo15.doc.2')
        WHERE schedule_id=new_id AND request_id=(SELECT id FROM appointment_request WHERE code='demo15.req.011');
    UPDATE appointment SET status='CONFIRMED' WHERE schedule_id=new_id AND status='CANDIDATE';
    UPDATE schedule SET status='PUBLISHED',final_fitness=0 WHERE id=new_id;

    IF (SELECT count(*) FROM appointment WHERE status='CONFIRMED')<>150 OR
       (SELECT count(*) FROM appointment WHERE schedule_id=new_id AND status='CONFIRMED')<>150 THEN
        RAISE EXCEPTION 'ASSERTION FAILED: replanning changed real reservation coverage';
    END IF;
    IF EXISTS (SELECT 1 FROM original_locks o LEFT JOIN appointment a ON a.id=o.id
        WHERE a.id IS NULL OR a.schedule_id<>new_id OR
        ROW(a.request_id,a.doctor_id,a.room_id,a.starts_at,a.ends_at,a.status,a.locked)
            IS DISTINCT FROM ROW(o.request_id,o.doctor_id,o.room_id,o.starts_at,o.ends_at,o.status,o.locked)) THEN
        RAISE EXCEPTION 'ASSERTION FAILED: an original lock was changed or duplicated';
    END IF;
    IF EXISTS (SELECT 1 FROM appointment a JOIN doctor_absence x
        ON x.doctor_id=a.doctor_id AND x.occupied_at && a.occupied_at WHERE a.status='CONFIRMED') THEN
        RAISE EXCEPTION 'ASSERTION FAILED: published replacement still intersects an absence';
    END IF;
    IF EXISTS (SELECT 1 FROM appointment a JOIN appointment_request q ON q.id=a.request_id
        WHERE a.status='CONFIRMED' AND NOT EXISTS (SELECT 1 FROM doctor_specialty ds
            WHERE ds.doctor_id=a.doctor_id AND ds.specialty_id=q.specialty_id)) THEN
        RAISE EXCEPTION 'ASSERTION FAILED: replacement doctor lacks specialty';
    END IF;
    IF EXISTS (SELECT 1 FROM appointment a WHERE a.status='CONFIRMED' AND NOT EXISTS (
        SELECT 1 FROM doctor_availability v WHERE v.doctor_id=a.doctor_id AND v.occupied_at @> a.occupied_at))
       OR EXISTS (SELECT 1 FROM appointment a JOIN doctor_break b
        ON b.doctor_id=a.doctor_id AND b.occupied_at && a.occupied_at WHERE a.status='CONFIRMED') THEN
        RAISE EXCEPTION 'ASSERTION FAILED: replacement violates doctor availability or a break';
    END IF;
    RAISE NOTICE 'PASS: one absence handled, 150 bookings retained, five original lock IDs preserved, only one published schedule.';
END;
$replan$;
ROLLBACK;
