\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path = hospital, public;
SET LOCAL TIME ZONE 'Europe/Paris';
-- The marker and every row commit together. Replays do not update user data or
-- repair a deliberately edited/deleted demo. Parallel loads serialize here.
SELECT pg_advisory_xact_lock(hashtext('project15:demo15_20261116_v1'));

DO $seed$
DECLARE
    dataset constant text := 'demo15_20261116_v1';
BEGIN
    IF EXISTS (SELECT 1 FROM demo_dataset WHERE dataset_key = dataset) THEN
        RAISE NOTICE 'Dataset % already loaded; all existing rows preserved.', dataset;
        RETURN;
    END IF;
    INSERT INTO demo_dataset VALUES
        (dataset, 'Fictional hospital, 16–20 November 2026. All identities and experiment measurements are synthetic.',
         timestamptz '2026-11-12 08:00:00+01');

    INSERT INTO hospital (code, name, time_zone, demo_dataset_key) VALUES
        ('demo15.hospital', 'Hôpital fictif des Jardins', 'Europe/Paris', dataset);

    INSERT INTO department (code, hospital_id, name, demo_dataset_key)
    SELECT 'demo15.dep.' || n, h.id, v.name, dataset
    FROM (VALUES (1,'Médecine'), (2,'Consultations chirurgicales'),
                 (3,'Femme et enfant'), (4,'Explorations'), (5,'Rééducation et vision')) v(n,name)
    CROSS JOIN hospital h WHERE h.code = 'demo15.hospital';

    INSERT INTO specialty (code, department_id, name, demo_dataset_key)
    SELECT 'demo15.sp.' || n, d.id, v.name, dataset
    FROM (VALUES (1,'Cardiologie'), (2,'Pneumologie'), (3,'Orthopédie'),
                 (4,'Dermatologie'), (5,'Pédiatrie'), (6,'Gynécologie'),
                 (7,'Radiologie'), (8,'Neurologie'), (9,'Physiothérapie'), (10,'Ophtalmologie')) v(n,name)
    JOIN department d ON d.code = 'demo15.dep.' || ((n + 1) / 2);

    INSERT INTO doctor (code, department_id, display_name, email, demo_dataset_key)
    SELECT 'demo15.doc.' || n, d.id, 'Dr ' || (ARRAY['Amel','Nadir','Lina','Sami','Nora','Yanis','Maya','Idris','Ines','Rami'])[(n-1)%10+1]
           || ' Fictif ' || lpad(n::text,2,'0'), 'doctor' || n || '@example.test', dataset
    FROM generate_series(1,20) n
    JOIN department d ON d.code = 'demo15.dep.' || ((n + 3) / 4);

    INSERT INTO doctor_specialty
    SELECT d.id, s.id FROM generate_series(1,20) n
    JOIN doctor d ON d.code = 'demo15.doc.' || n
    JOIN specialty s ON s.code = 'demo15.sp.' || ((n+1)/2);
    -- Two doctors also have the neighbouring specialty in their department.
    INSERT INTO doctor_specialty
    SELECT d.id, s.id FROM (VALUES (2,2),(6,4)) v(doc_no,sp_no)
    JOIN doctor d ON d.code = 'demo15.doc.' || doc_no
    JOIN specialty s ON s.code = 'demo15.sp.' || sp_no;

    INSERT INTO room_type (code, name, demo_dataset_key)
    SELECT 'demo15.type.' || n, name, dataset
    FROM (VALUES (1,'Consultation'),(2,'Imagerie'),(3,'Exploration neurologique'),(4,'Examen ophtalmologique')) v(n,name);

    INSERT INTO room (code, department_id, room_type_id, name, demo_dataset_key)
    SELECT 'demo15.room.' || n, d.id, rt.id, 'Salle fictive ' || lpad(n::text,2,'0'), dataset
    FROM generate_series(1,12) n
    JOIN department d ON d.code = 'demo15.dep.' || CASE WHEN n <= 10 THEN (n+1)/2 ELSE n-10 END
    JOIN room_type rt ON rt.code = 'demo15.type.' || CASE n WHEN 7 THEN 2 WHEN 8 THEN 3 WHEN 10 THEN 4 ELSE 1 END;

    INSERT INTO equipment (code, name, demo_dataset_key)
    SELECT 'demo15.eq.' || n, name, dataset
    FROM (VALUES (1,'Électrocardiographe'),(2,'Spiromètre'),(3,'Échographe'),
                 (4,'Dermatoscope'),(5,'Balance pédiatrique'),(6,'Cardiotocographe'),
                 (7,'Radiographie'),(8,'Électroencéphalographe'),
                 (9,'Table de rééducation'),(10,'Lampe à fente')) v(n,name);

    INSERT INTO room_equipment
    SELECT r.id, e.id, 1 FROM generate_series(1,10) n
    JOIN room r ON r.code = 'demo15.room.' || n
    JOIN equipment e ON e.code = 'demo15.eq.' || n;
    INSERT INTO room_equipment
    SELECT r.id, e.id, 1 FROM generate_series(2,12) n
    JOIN room r ON r.code = 'demo15.room.' || n
    JOIN equipment e ON e.code = 'demo15.eq.1';

    INSERT INTO patient (code, display_name, email, demo_dataset_key)
    SELECT 'demo15.patient.' || lpad(n::text,3,'0'),
           (ARRAY['Salma','Adam','Aya','Omar','Meriem','Noe','Sara','Ilyan','Louna','Elias'])[(n-1)%10+1]
           || ' Démonstration ' || lpad(n::text,3,'0'), 'patient' || n || '@example.test', dataset
    FROM generate_series(1,130) n;

    INSERT INTO doctor_availability (doctor_id, starts_at, ends_at)
    SELECT d.id,
           (date '2026-11-16' + day_no + CASE shift WHEN 0 THEN time '08:00' + (n%2)*interval '30 minutes' ELSE time '13:00' END)
                AT TIME ZONE 'Europe/Paris',
           (date '2026-11-16' + day_no + CASE shift WHEN 0 THEN time '12:00' ELSE time '16:00' + (n%3)*interval '30 minutes' END)
                AT TIME ZONE 'Europe/Paris'
    FROM generate_series(1,20) n CROSS JOIN generate_series(0,4) day_no CROSS JOIN generate_series(0,1) shift
    JOIN doctor d ON d.code = 'demo15.doc.' || n
    WHERE NOT (n%2=0 AND day_no=4 AND shift=0);

    INSERT INTO doctor_break (doctor_id, starts_at, ends_at, label)
    SELECT d.id, (date '2026-11-16' + day_no + time '12:00') AT TIME ZONE 'Europe/Paris',
           (date '2026-11-16' + day_no + time '13:00') AT TIME ZONE 'Europe/Paris', 'Déjeuner'
    FROM generate_series(1,20) n CROSS JOIN generate_series(0,4) day_no
    JOIN doctor d ON d.code = 'demo15.doc.' || n;

    INSERT INTO doctor_absence (doctor_id, starts_at, ends_at, reason)
    SELECT d.id, v.starts_at, v.ends_at, 'Absence fictive planifiée'
    FROM (VALUES (2,timestamptz '2026-11-17 08:00+01',timestamptz '2026-11-17 12:00+01'),
                 (6,timestamptz '2026-11-17 08:00+01',timestamptz '2026-11-17 12:00+01'),
                 (10,timestamptz '2026-11-17 08:00+01',timestamptz '2026-11-17 12:00+01'),
                 (3,timestamptz '2026-11-19 13:00+01',timestamptz '2026-11-19 17:00+01'),
                 (7,timestamptz '2026-11-19 13:00+01',timestamptz '2026-11-19 17:00+01')) v(n,starts_at,ends_at)
    JOIN doctor d ON d.code = 'demo15.doc.' || n;

    -- Construct a feasible witness first, then derive requests and windows from
    -- it. No independently random resource assignment can make a request orphan.
    CREATE TEMP TABLE demo_assignment ON COMMIT DROP AS
    WITH numbered AS (
        SELECT n, (n-1)%10+1 AS sp_no, (n-1)/30 AS day_no,
               ((n-1)/10)%3 AS slot_no, (n-1)%130+1 AS patient_no
        FROM generate_series(1,150) n
    ), timed AS (
        SELECT *, sp_no*2-CASE WHEN slot_no=2 THEN 0 ELSE 1 END AS doctor_no,
               CASE slot_no WHEN 0 THEN CASE WHEN sp_no%3=1 THEN 60 ELSE 45 END
                            WHEN 1 THEN CASE WHEN sp_no%2=0 THEN 30 ELSE 15 END
                            ELSE CASE WHEN sp_no%2=0 THEN 45 ELSE 30 END END AS duration_minutes,
               (date '2026-11-16' + day_no + CASE slot_no WHEN 0 THEN time '09:00'
                                  WHEN 1 THEN time '10:00' ELSE time '14:00' END)
                   AT TIME ZONE 'Europe/Paris' AS starts_at
        FROM numbered
    ) SELECT *, starts_at + duration_minutes*interval '1 minute' AS ends_at FROM timed;

    INSERT INTO appointment_request
        (code, patient_id, specialty_id, required_room_type_id, duration_minutes, urgency, requested_at, demo_dataset_key)
    SELECT 'demo15.req.' || lpad(a.n::text,3,'0'), p.id, s.id,
           CASE WHEN a.sp_no IN (7,8,10) THEN r.room_type_id END,
           a.duration_minutes,
           CASE WHEN a.n%15=0 THEN 'URGENT' WHEN a.n%5=0 THEN 'PRIORITY' ELSE 'ROUTINE' END,
           CASE WHEN a.n=150 THEN timestamptz '2026-11-20 13:30+01'
                ELSE timestamptz '2026-11-12 09:00+01' + (a.n%24)*interval '10 minutes' END, dataset
    FROM demo_assignment a
    JOIN patient p ON p.code = 'demo15.patient.' || lpad(a.patient_no::text,3,'0')
    JOIN specialty s ON s.code = 'demo15.sp.' || a.sp_no
    JOIN room r ON r.code = 'demo15.room.' || a.sp_no;

    INSERT INTO patient_availability (patient_id, starts_at, ends_at)
    SELECT p.id, a.starts_at - CASE WHEN a.n%5=0 THEN interval '0' ELSE interval '30 minutes' END,
           a.ends_at + CASE WHEN a.n%5=0 THEN interval '0' ELSE interval '30 minutes' END
    FROM demo_assignment a
    JOIN patient p ON p.code = 'demo15.patient.' || lpad(a.patient_no::text,3,'0');
    INSERT INTO patient_availability (patient_id, starts_at, ends_at)
    SELECT p.id,
           (date '2026-11-16' + a.day_no + CASE WHEN a.slot_no=2 THEN time '08:00' ELSE time '15:30' END)
               AT TIME ZONE 'Europe/Paris',
           (date '2026-11-16' + a.day_no + CASE WHEN a.slot_no=2 THEN time '08:45' ELSE time '16:30' END)
               AT TIME ZONE 'Europe/Paris'
    FROM demo_assignment a
    JOIN patient p ON p.code = 'demo15.patient.' || lpad(a.patient_no::text,3,'0')
    WHERE a.n%3=0;

    INSERT INTO request_availability
    SELECT q.id, q.patient_id, pa.id FROM demo_assignment a
    JOIN appointment_request q ON q.code = 'demo15.req.' || lpad(a.n::text,3,'0')
    JOIN patient_availability pa ON pa.patient_id=q.patient_id
         AND (pa.starts_at AT TIME ZONE 'Europe/Paris')::date = date '2026-11-16' + a.day_no;

    INSERT INTO patient_time_preference (patient_id, iso_weekday, local_start, local_end, weight)
    SELECT p.id, ((n/5-1)%5+1)::smallint, time '09:00', time '11:00', 2
    FROM generate_series(5,130,5) n
    JOIN patient p ON p.code = 'demo15.patient.' || lpad(n::text,3,'0');

    INSERT INTO request_equipment
    SELECT q.id, e.id, 1 FROM demo_assignment a
    JOIN appointment_request q ON q.code = 'demo15.req.' || lpad(a.n::text,3,'0')
    JOIN equipment e ON e.code = 'demo15.eq.' || a.sp_no WHERE a.n%3=0;
    INSERT INTO request_equipment
    SELECT q.id, e.id, 1 FROM demo_assignment a
    JOIN appointment_request q ON q.code = 'demo15.req.' || lpad(a.n::text,3,'0')
    JOIN equipment e ON e.code = 'demo15.eq.1' WHERE a.n%10=0;

    INSERT INTO algorithm (code, name, version, demo_dataset_key) VALUES
        ('demo15.genetic','Genetic algorithm','synthetic-v1',dataset),
        ('demo15.annealing','Simulated annealing','synthetic-v1',dataset);
    INSERT INTO schedule_week (code, hospital_id, starts_on, demo_dataset_key)
    SELECT 'demo15.week', id, date '2026-11-16', dataset FROM hospital WHERE code='demo15.hospital';

    INSERT INTO schedule (code, schedule_week_id, algorithm_id, parameters, random_seed, final_fitness, status, created_at, demo_dataset_key)
    SELECT 'demo15.schedule.' || v.name, w.id, alg.id, '{"fixture":true,"synthetic":true}'::jsonb,
           101, v.fitness, v.status, timestamptz '2026-11-13 10:00+01', dataset
    FROM (VALUES ('published','PUBLISHED',0),('candidate-a','CANDIDATE',0),('candidate-b','CANDIDATE',3000)) v(name,status,fitness)
    CROSS JOIN schedule_week w CROSS JOIN algorithm alg
    WHERE w.code='demo15.week' AND alg.code='demo15.genetic';

    INSERT INTO appointment (code, schedule_id, request_id, doctor_id, room_id, starts_at, ends_at, status, locked)
    SELECT 'demo15.appt.published.' || lpad(a.n::text,3,'0'), sc.id, q.id, d.id, r.id,
           a.starts_at, a.ends_at, 'CONFIRMED', a.n<=5
    FROM demo_assignment a
    JOIN schedule sc ON sc.code='demo15.schedule.published'
    JOIN appointment_request q ON q.code='demo15.req.' || lpad(a.n::text,3,'0')
    JOIN doctor d ON d.code='demo15.doc.' || a.doctor_no
    JOIN room r ON r.code='demo15.room.' || a.sp_no;

    INSERT INTO appointment (code, schedule_id, request_id, doctor_id, room_id, starts_at, ends_at, status, carried_from_appointment_id)
    SELECT 'demo15.appt.' || c.name || '.' || lpad(a.n::text,3,'0'), sc.id, pub.request_id, pub.doctor_id, pub.room_id,
           CASE WHEN c.name='candidate-b' AND a.n=11 THEN timestamptz '2026-11-16 09:15+01' ELSE a.starts_at END,
           CASE WHEN c.name='candidate-b' AND a.n=11 THEN timestamptz '2026-11-16 09:15+01' + a.duration_minutes*interval '1 minute' ELSE a.ends_at END,
           'CANDIDATE', CASE WHEN pub.locked THEN pub.id END
    FROM demo_assignment a CROSS JOIN (VALUES ('candidate-a'),('candidate-b')) c(name)
    JOIN schedule sc ON sc.code='demo15.schedule.' || c.name
    JOIN appointment pub ON pub.code='demo15.appt.published.' || lpad(a.n::text,3,'0');

    INSERT INTO benchmark_instance
        (code,data_version,name,schedule_week_id,request_count,definition,known_optimum,optimum_evidence,demo_dataset_key)
    VALUES ('demo15.instance.tiny','v1','Two-request reference',NULL,2,
        '{"synthetic":true,"time_zone":"Europe/Paris","doctor":"tiny-d1","room":"tiny-r1","availability":["2026-11-16T09:00:00+01:00","2026-11-16T10:00:00+01:00"],"requests":[{"id":"tiny-q1","duration_minutes":30,"ideal_start":"2026-11-16T09:00:00+01:00"},{"id":"tiny-q2","duration_minutes":30,"ideal_start":"2026-11-16T09:30:00+01:00"}],"requirements":"Both requests use the same specialty, doctor and room; both patients available for the full hour; no equipment, breaks, absences or preferences.","witness":[["tiny-q1","09:00","09:30"],["tiny-q2","09:30","10:00"]]}'::jsonb,
        0,'Objective = 1000 * hard violations + nonnegative delay minutes from ideal_start. The witness has no violations and zero delay, hence reaches the lower bound 0.',dataset);
    INSERT INTO benchmark_instance
        (code,data_version,name,schedule_week_id,request_count,definition,demo_dataset_key)
    SELECT 'demo15.instance.week','v1','150-request hospital week',w.id,150,
        jsonb_build_object('synthetic',true,'dataset_key',dataset,'time_zone','Europe/Paris',
            'request_codes',(SELECT jsonb_agg(code ORDER BY code) FROM appointment_request WHERE demo_dataset_key=dataset),
            'objective','1000 * hard violations + nonnegative delay minutes from ideal_start',
            'ideal_start','Use each published demo appointment start as the fixture ideal; patient_time_preference is available for alternative objective definitions.'), dataset
    FROM schedule_week w WHERE w.code='demo15.week';

    INSERT INTO experiment (code,instance_id,name,objective,synthetic,description,demo_dataset_key)
    SELECT 'demo15.experiment.' || CASE WHEN i.request_count=2 THEN 'tiny' ELSE 'week' END, i.id,
           'SYNTHETIC illustration — ' || i.name,
           '1000 * hard violations + nonnegative delay minutes from ideal_start',true,
           'SYNTHETIC generated measurements, not benchmark results. Two algorithms, seeds 1..20 and equal 2000 ms budgets.',dataset
    FROM benchmark_instance i WHERE i.demo_dataset_key=dataset;

    INSERT INTO experiment_run
        (code,experiment_id,algorithm_id,random_seed,parameters,budget_ms,elapsed_ms,status,final_fitness,hard_violations,synthetic,started_at,demo_dataset_key)
    SELECT 'demo15.run.' || kind || '.' || alg_kind || '.' || lpad(seed::text,2,'0'), e.id, alg.id, seed,
           CASE alg_kind WHEN 'genetic' THEN '{"population":40,"mutation_rate":0.1}'::jsonb
                         ELSE '{"initial_temperature":100,"cooling_rate":0.95}'::jsonb END,
           2000, 1400 + seed*10 + CASE alg_kind WHEN 'genetic' THEN 0 ELSE 100 END,
           'COMPLETED',
           CASE kind WHEN 'tiny' THEN (seed + CASE alg_kind WHEN 'genetic' THEN 0 ELSE 1 END)%5
                     ELSE (seed%3)*1000 + 8 + (seed%7)*3 + CASE alg_kind WHEN 'genetic' THEN 0 ELSE 4 END END,
           CASE kind WHEN 'tiny' THEN 0 ELSE seed%3 END,
           true, timestamptz '2026-11-13 12:00+01' + seed*interval '1 minute',dataset
    FROM (VALUES ('tiny'),('week')) kinds(kind)
    CROSS JOIN (VALUES ('genetic'),('annealing')) algs(alg_kind)
    CROSS JOIN generate_series(1,20) seed
    JOIN experiment e ON e.code='demo15.experiment.' || kind
    JOIN algorithm alg ON alg.code='demo15.' || alg_kind;

    INSERT INTO fitness_point (run_id,step,elapsed_ms,fitness)
    SELECT r.id, step, r.elapsed_ms*step/20,
           r.final_fitness + (20-step)*(20-step+5)
    FROM experiment_run r CROSS JOIN generate_series(0,20) step
    WHERE r.demo_dataset_key=dataset;
END;
$seed$;
COMMIT;
