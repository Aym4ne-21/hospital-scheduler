SET LOCAL search_path = hospital, public;

CREATE TABLE algorithm (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    version text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE schedule_week (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    hospital_id bigint NOT NULL REFERENCES hospital(id),
    starts_on date NOT NULL CHECK (isfinite(starts_on) AND extract(isodow FROM starts_on) = 1),
    demo_dataset_key text REFERENCES demo_dataset(dataset_key),
    UNIQUE (hospital_id, starts_on)
);

CREATE TABLE schedule (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    schedule_week_id bigint NOT NULL REFERENCES schedule_week(id),
    algorithm_id bigint NOT NULL REFERENCES algorithm(id),
    parameters jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parameters) = 'object'),
    random_seed bigint NOT NULL,
    final_fitness numeric,
    status text NOT NULL CHECK (status IN ('DRAFT', 'RUNNING', 'CANDIDATE', 'PUBLISHED', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key),
    CONSTRAINT schedule_fitness_value CHECK (final_fitness IS NULL OR
        (final_fitness >= 0 AND final_fitness < 'Infinity'::numeric)),
    CONSTRAINT schedule_final_result CHECK
        (status NOT IN ('CANDIDATE', 'PUBLISHED') OR final_fitness IS NOT NULL)
);

CREATE UNIQUE INDEX one_published_schedule_per_week
    ON schedule(schedule_week_id) WHERE status = 'PUBLISHED';
CREATE INDEX schedule_algorithm_idx ON schedule(algorithm_id);
CREATE INDEX schedule_week_status_idx ON schedule(schedule_week_id, status);

CREATE TABLE appointment (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    schedule_id bigint NOT NULL REFERENCES schedule(id),
    request_id bigint NOT NULL REFERENCES appointment_request(id),
    doctor_id bigint REFERENCES doctor(id),
    room_id bigint REFERENCES room(id),
    starts_at timestamptz,
    ends_at timestamptz,
    occupied_at tstzrange GENERATED ALWAYS AS
        (CASE WHEN starts_at IS NOT NULL AND ends_at IS NOT NULL AND ends_at > starts_at
              THEN tstzrange(starts_at, ends_at, '[)') END) STORED,
    status text NOT NULL DEFAULT 'CANDIDATE'
        CHECK (status IN ('CANDIDATE', 'CONFIRMED', 'CANCELLED')),
    locked boolean NOT NULL DEFAULT false,
    carried_from_appointment_id bigint REFERENCES appointment(id),
    CONSTRAINT appointment_request_per_schedule UNIQUE (schedule_id, request_id),
    CONSTRAINT appointment_bounds CHECK (
        (starts_at IS NULL AND ends_at IS NULL) OR
        (starts_at IS NOT NULL AND ends_at IS NOT NULL
         AND isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at)),
    CONSTRAINT appointment_confirmed_complete CHECK (status <> 'CONFIRMED' OR
        (doctor_id IS NOT NULL AND room_id IS NOT NULL
         AND starts_at IS NOT NULL AND ends_at IS NOT NULL)),
    CONSTRAINT appointment_lock_confirmed CHECK (NOT locked OR status = 'CONFIRMED'),
    CHECK (carried_from_appointment_id IS NULL OR carried_from_appointment_id <> id),
    -- No schedule_id here: both exclusions protect real reservations globally.
    CONSTRAINT confirmed_doctor_no_overlap EXCLUDE USING gist
        (doctor_id WITH =, occupied_at WITH &&) WHERE (status = 'CONFIRMED'),
    CONSTRAINT confirmed_room_no_overlap EXCLUDE USING gist
        (room_id WITH =, occupied_at WITH &&) WHERE (status = 'CONFIRMED')
);

CREATE INDEX appointment_request_idx ON appointment(request_id);
CREATE INDEX appointment_doctor_time_idx ON appointment(doctor_id, starts_at);
CREATE INDEX appointment_room_time_idx ON appointment(room_id, starts_at);
CREATE INDEX appointment_carried_from_idx ON appointment(carried_from_appointment_id);

COMMENT ON TABLE appointment IS
    'Candidate conflicts are allowed. Confirmed doctor/room reservations exclude overlaps across all schedules. Domain feasibility and lock preservation are validated by the application before publication.';
COMMENT ON COLUMN appointment.carried_from_appointment_id IS
    'A candidate copy may refer to an existing locked reservation; it must remain CANDIDATE while the original is CONFIRMED. This FK alone does not enforce matching resources/times.';
