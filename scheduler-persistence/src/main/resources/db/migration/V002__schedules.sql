SET LOCAL search_path = hospital, public;

CREATE TABLE algorithm (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    version text NOT NULL
);

CREATE TABLE schedule_week (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    hospital_id bigint NOT NULL REFERENCES hospital(id),
    starts_on date NOT NULL CHECK (isfinite(starts_on) AND extract(isodow FROM starts_on) = 1),
    UNIQUE (hospital_id, starts_on)
);

-- Each row is a saved version; several candidates can belong to the same week.
CREATE TABLE schedule (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    schedule_week_id bigint NOT NULL REFERENCES schedule_week(id),
    algorithm_id bigint NOT NULL REFERENCES algorithm(id),
    parameters jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parameters) = 'object'),
    random_seed bigint NOT NULL,
    final_fitness numeric,
    status text NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'RUNNING', 'CANDIDATE', 'CONFIRMED', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp CHECK (isfinite(created_at)),
    CONSTRAINT schedule_fitness_value CHECK (final_fitness IS NULL OR
        (final_fitness >= 0 AND final_fitness < 'Infinity'::numeric)),
    CONSTRAINT schedule_final_result CHECK
        (status NOT IN ('CANDIDATE', 'CONFIRMED') OR final_fitness IS NOT NULL)
);

-- Candidates must be structurally valid, but may contain resource conflicts.
CREATE TABLE candidate_assignment (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id bigint NOT NULL REFERENCES schedule(id),
    request_id bigint NOT NULL REFERENCES appointment_request(id),
    doctor_id bigint NOT NULL REFERENCES doctor(id),
    room_id bigint NOT NULL REFERENCES room(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    occupied_at tstzrange GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    locked boolean NOT NULL DEFAULT false,
    CONSTRAINT candidate_assignment_bounds CHECK
        (isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at),
    UNIQUE (schedule_id, request_id),
    -- Lets the reservation FK protect the exact saved assignment being confirmed.
    UNIQUE (id, request_id, doctor_id, room_id, starts_at, ends_at)
);

CREATE TABLE confirmed_appointment (
    candidate_assignment_id bigint PRIMARY KEY,
    request_id bigint NOT NULL UNIQUE,
    doctor_id bigint NOT NULL,
    room_id bigint NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    occupied_at tstzrange GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    confirmed_at timestamptz NOT NULL DEFAULT current_timestamp CHECK (isfinite(confirmed_at)),
    CONSTRAINT confirmed_appointment_assignment_fkey
        FOREIGN KEY (candidate_assignment_id, request_id, doctor_id, room_id, starts_at, ends_at)
        REFERENCES candidate_assignment(id, request_id, doctor_id, room_id, starts_at, ends_at),
    CONSTRAINT confirmed_appointment_bounds CHECK
        (isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at)
);

CREATE INDEX schedule_week_status_idx ON schedule(schedule_week_id, status);
CREATE INDEX schedule_algorithm_idx ON schedule(algorithm_id);
CREATE INDEX candidate_assignment_request_idx ON candidate_assignment(request_id);
CREATE INDEX candidate_assignment_doctor_time_idx ON candidate_assignment(doctor_id, starts_at);
CREATE INDEX candidate_assignment_room_time_idx ON candidate_assignment(room_id, starts_at);
CREATE INDEX confirmed_appointment_doctor_time_idx ON confirmed_appointment(doctor_id, starts_at);
CREATE INDEX confirmed_appointment_room_time_idx ON confirmed_appointment(room_id, starts_at);

COMMENT ON TABLE candidate_assignment IS
    'Saved proposed assignments; overlapping resources are allowed for optimization and fitness evaluation.';
COMMENT ON TABLE confirmed_appointment IS
    'Operational reservations referencing exact saved assignments. Day 2 task 3 adds global doctor/room overlap exclusions before confirmation is enabled.';
