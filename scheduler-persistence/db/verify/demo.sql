\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path = hospital, public;
SET LOCAL TIME ZONE 'Europe/Paris';
CREATE FUNCTION pg_temp.assert_true(ok boolean,label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF ok IS DISTINCT FROM true THEN RAISE EXCEPTION 'ASSERTION FAILED: %',label; END IF;
END;
$$;

CREATE TEMP TABLE expected_counts(table_name text PRIMARY KEY, expected bigint NOT NULL);
INSERT INTO expected_counts VALUES
    ('demo_dataset',1),('hospital',1),('department',5),('specialty',10),('doctor',20),
    ('doctor_specialty',22),('room_type',4),('room',12),('equipment',10),('room_equipment',21),
    ('patient',130),('doctor_availability',190),('doctor_absence',5),('doctor_break',100),
    ('patient_availability',200),('patient_time_preference',26),('appointment_request',150),
    ('request_availability',200),('request_equipment',65),('algorithm',2),('schedule_week',1),
    ('schedule',3),('appointment',450),('benchmark_instance',2),('experiment',2),
    ('experiment_run',80),('fitness_point',1680);
CREATE TEMP TABLE measured_counts(table_name text PRIMARY KEY, actual bigint NOT NULL);
DO $$
DECLARE t record; actual_count bigint;
BEGIN
    PERFORM pg_temp.assert_true((SELECT count(*)=27 FROM pg_tables WHERE schemaname='hospital'),
        'every application table has a count assertion');
    FOR t IN SELECT * FROM expected_counts ORDER BY table_name LOOP
        EXECUTE format('SELECT count(*) FROM hospital.%I',t.table_name) INTO actual_count;
        INSERT INTO measured_counts VALUES (t.table_name,actual_count);
        PERFORM pg_temp.assert_true(actual_count=t.expected,
            format('%s: expected %s rows, got %s',t.table_name,t.expected,actual_count));
    END LOOP;
END;
$$;

CREATE TEMP VIEW confirmed_demo AS
SELECT a.*, q.patient_id,q.specialty_id,q.required_room_type_id,q.duration_minutes,q.requested_at,
       w.starts_on AS week_start,h.time_zone,h.id AS hospital_id
FROM appointment a JOIN appointment_request q ON q.id=a.request_id
JOIN schedule s ON s.id=a.schedule_id JOIN schedule_week w ON w.id=s.schedule_week_id
JOIN hospital h ON h.id=w.hospital_id
WHERE a.status='CONFIRMED' AND q.demo_dataset_key='demo15_20261116_v1';

DO $checks$
BEGIN
    PERFORM pg_temp.assert_true((SELECT count(*)=150 AND count(DISTINCT request_id)=150 FROM confirmed_demo),
        '150 distinct confirmed requests');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM appointment_request q WHERE q.demo_dataset_key='demo15_20261116_v1'
        AND NOT EXISTS (SELECT 1 FROM confirmed_demo a WHERE a.request_id=q.id)), 'every request is covered');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a WHERE a.ends_at-a.starts_at<>a.duration_minutes*interval '1 minute'), 'exact request durations');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a WHERE NOT EXISTS (
            SELECT 1 FROM doctor_specialty ds WHERE ds.doctor_id=a.doctor_id AND ds.specialty_id=a.specialty_id)), 'doctor specialties');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a WHERE NOT EXISTS (
            SELECT 1 FROM doctor_availability v WHERE v.doctor_id=a.doctor_id AND v.occupied_at @> a.occupied_at)), 'doctor windows contain appointments');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a JOIN doctor_absence x ON x.doctor_id=a.doctor_id AND x.occupied_at && a.occupied_at), 'no absence overlap');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a JOIN doctor_break x ON x.doctor_id=a.doctor_id AND x.occupied_at && a.occupied_at), 'no lunch overlap');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a WHERE NOT EXISTS (
            SELECT 1 FROM request_availability ra JOIN patient_availability v ON v.id=ra.patient_availability_id
            WHERE ra.request_id=a.request_id AND v.patient_id=a.patient_id AND v.occupied_at @> a.occupied_at)), 'patient/request windows');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a JOIN room r ON r.id=a.room_id
        WHERE a.required_room_type_id IS NOT NULL AND a.required_room_type_id<>r.room_type_id), 'required room types');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a JOIN request_equipment needed ON needed.request_id=a.request_id
        LEFT JOIN room_equipment present ON present.room_id=a.room_id AND present.equipment_id=needed.equipment_id
        WHERE coalesce(present.quantity,0)<needed.quantity), 'all required equipment and quantities');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a JOIN doctor d ON d.id=a.doctor_id JOIN department dd ON dd.id=d.department_id
        JOIN room r ON r.id=a.room_id JOIN department rd ON rd.id=r.department_id
        JOIN specialty sp ON sp.id=a.specialty_id JOIN department sd ON sd.id=sp.department_id
        WHERE dd.hospital_id<>a.hospital_id OR rd.hospital_id<>a.hospital_id OR sd.hospital_id<>a.hospital_id), 'same hospital');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a WHERE (a.starts_at AT TIME ZONE a.time_zone)<a.week_start::timestamp
        OR (a.ends_at AT TIME ZONE a.time_zone)>(a.week_start+7)::timestamp OR a.requested_at>a.starts_at), 'week boundaries and request chronology');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM confirmed_demo a JOIN confirmed_demo b ON a.id<b.id AND a.occupied_at && b.occupied_at
        WHERE a.doctor_id=b.doctor_id OR a.room_id=b.room_id OR a.patient_id=b.patient_id), 'no doctor, room or patient overlaps');
    PERFORM pg_temp.assert_true(EXISTS (
        SELECT 1 FROM confirmed_demo a JOIN confirmed_demo b ON a.id<>b.id AND a.doctor_id=b.doctor_id
        AND a.room_id=b.room_id AND a.ends_at=b.starts_at), 'real adjacent demo appointments exist');
    PERFORM pg_temp.assert_true((SELECT count(*)=5 FROM confirmed_demo WHERE locked), 'five original locked appointments');
    PERFORM pg_temp.assert_true((SELECT count(*)=20 FROM (
        SELECT patient_id FROM appointment_request GROUP BY patient_id HAVING count(*)=2) x), 'twenty patients have two requests');
    PERFORM pg_temp.assert_true((SELECT count(*)=10 FROM appointment_request WHERE urgency='URGENT')
        AND (SELECT count(*)=20 FROM appointment_request WHERE urgency='PRIORITY')
        AND (SELECT count(*)=120 FROM appointment_request WHERE urgency='ROUTINE'), 'urgency mix');

    PERFORM pg_temp.assert_true((SELECT count(*)=2 FROM schedule WHERE status='CANDIDATE'), 'two candidate schedules');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM schedule s WHERE s.status='CANDIDATE' AND
        ((SELECT count(*) FROM appointment a WHERE a.schedule_id=s.id AND a.status='CANDIDATE')<>150 OR EXISTS (
            SELECT q.id FROM appointment_request q WHERE q.demo_dataset_key='demo15_20261116_v1'
            EXCEPT SELECT a.request_id FROM appointment a WHERE a.schedule_id=s.id))), 'each candidate contains the same 150 requests');
    PERFORM pg_temp.assert_true((SELECT count(*)=10 FROM appointment WHERE carried_from_appointment_id IS NOT NULL), 'five lock references per alternative');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM appointment c JOIN appointment original ON original.id=c.carried_from_appointment_id
        WHERE NOT original.locked OR original.status<>'CONFIRMED' OR c.status<>'CANDIDATE' OR c.locked
        OR ROW(c.request_id,c.doctor_id,c.room_id,c.starts_at,c.ends_at)
             IS DISTINCT FROM ROW(original.request_id,original.doctor_id,original.room_id,original.starts_at,original.ends_at)), 'locked reservations represented without second bookings');
    PERFORM pg_temp.assert_true((SELECT count(*)=1 FROM appointment a JOIN appointment b
        ON a.id<b.id AND a.schedule_id=b.schedule_id AND a.doctor_id=b.doctor_id AND a.occupied_at && b.occupied_at
        JOIN schedule s ON s.id=a.schedule_id WHERE s.code='demo15.schedule.candidate-b'), 'candidate B: exactly one doctor conflict');
    PERFORM pg_temp.assert_true((SELECT count(*)=1 FROM appointment a JOIN appointment b
        ON a.id<b.id AND a.schedule_id=b.schedule_id AND a.room_id=b.room_id AND a.occupied_at && b.occupied_at
        JOIN schedule s ON s.id=a.schedule_id WHERE s.code='demo15.schedule.candidate-b'), 'candidate B: exactly one room conflict');
    PERFORM pg_temp.assert_true((SELECT count(*)=1 FROM appointment a JOIN schedule s ON s.id=a.schedule_id
        WHERE s.code='demo15.schedule.candidate-b' AND NOT EXISTS (
            SELECT 1 FROM request_availability ra JOIN patient_availability pa ON pa.id=ra.patient_availability_id
            WHERE ra.request_id=a.request_id AND pa.occupied_at @> a.occupied_at)), 'candidate B: exactly one patient window violation');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM appointment a JOIN appointment b ON a.id<b.id AND a.schedule_id=b.schedule_id AND a.occupied_at && b.occupied_at
        JOIN schedule s ON s.id=a.schedule_id WHERE s.code='demo15.schedule.candidate-a'
        AND (a.doctor_id=b.doctor_id OR a.room_id=b.room_id)), 'candidate A has no internal resource conflict');

    PERFORM pg_temp.assert_true(NOT EXISTS (SELECT 1 FROM experiment WHERE NOT synthetic)
        AND NOT EXISTS (SELECT 1 FROM experiment_run WHERE NOT synthetic), 'all experiment measurements labelled synthetic');
    PERFORM pg_temp.assert_true((SELECT count(*)=4 FROM (
        SELECT experiment_id,algorithm_id FROM experiment_run GROUP BY experiment_id,algorithm_id
        HAVING count(*)=20 AND count(DISTINCT random_seed)=20 AND min(random_seed)=1 AND max(random_seed)=20
           AND min(budget_ms)=2000 AND max(budget_ms)=2000) groups), 'four balanced groups, same seeds and budget');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM experiment_run WHERE elapsed_ms>budget_ms OR status<>'COMPLETED'
        OR final_fitness IS NULL OR final_fitness < hard_violations*1000), 'complete and consistent synthetic results');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM experiment_run r LEFT JOIN LATERAL (
            SELECT count(*) AS n,min(step) AS first_step,max(step) AS last_step FROM fitness_point p WHERE p.run_id=r.id
        ) c ON true WHERE c.n<>21 OR c.first_step<>0 OR c.last_step<>20), '21 contiguous points per run');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM experiment_run r JOIN LATERAL (
            SELECT fitness,elapsed_ms FROM fitness_point WHERE run_id=r.id ORDER BY step DESC LIMIT 1
        ) p ON true WHERE p.fitness<>r.final_fitness OR p.elapsed_ms<>r.elapsed_ms), 'last point equals final fitness and elapsed time');
    PERFORM pg_temp.assert_true(NOT EXISTS (
        SELECT 1 FROM (SELECT run_id,step,fitness,elapsed_ms,
            lag(fitness) OVER (PARTITION BY run_id ORDER BY step) AS previous_fitness,
            lag(elapsed_ms) OVER (PARTITION BY run_id ORDER BY step) AS previous_elapsed
            FROM fitness_point) p WHERE fitness>previous_fitness OR elapsed_ms<previous_elapsed), 'best-so-far curves and ordered times');
    PERFORM pg_temp.assert_true((SELECT known_optimum=0 AND optimum_evidence IS NOT NULL
        AND jsonb_array_length(definition->'requests')=2 FROM benchmark_instance WHERE code='demo15.instance.tiny'), 'reference optimum has a witness');
    PERFORM pg_temp.assert_true((SELECT known_optimum IS NULL AND optimum_evidence IS NULL
        FROM benchmark_instance WHERE code='demo15.instance.week'), 'weekly optimum left uncertified');
    RAISE NOTICE 'PASS: all 27 table counts, confirmed feasibility, candidate conflicts, lock references, balanced synthetic runs and convergence.';
END;
$checks$;

-- Independently check the tiny optimum witness: two ideal 30-minute slots,
-- common resources, no overlap, both within the full-hour availability.
WITH tiny AS (SELECT definition FROM benchmark_instance WHERE code='demo15.instance.tiny'), slots AS (
    SELECT (q->>'ideal_start')::timestamptz AS starts_at,
           (q->>'ideal_start')::timestamptz+(q->>'duration_minutes')::integer*interval '1 minute' AS ends_at,
           tstzrange((definition->'availability'->>0)::timestamptz,(definition->'availability'->>1)::timestamptz,'[)') AS available
    FROM tiny CROSS JOIN LATERAL jsonb_array_elements(definition->'requests') q
)
SELECT pg_temp.assert_true((SELECT count(*)=2 AND bool_and(available @> tstzrange(starts_at,ends_at,'[)'))
    AND sum(extract(epoch FROM ends_at-starts_at))/60=60 FROM slots)
    AND NOT EXISTS (SELECT 1 FROM slots a JOIN slots b ON a.starts_at<b.starts_at
        AND tstzrange(a.starts_at,a.ends_at,'[)') && tstzrange(b.starts_at,b.ends_at,'[)')), 'tiny optimum zero is achievable');

SELECT table_name,actual FROM measured_counts ORDER BY table_name;
SELECT urgency,count(*) AS requests FROM appointment_request GROUP BY urgency ORDER BY urgency;
SELECT s.code,s.status,count(*) AS appointments,count(*) FILTER (WHERE a.locked) AS locked
FROM schedule s JOIN appointment a ON a.schedule_id=s.id GROUP BY s.id ORDER BY s.code;
ROLLBACK;
