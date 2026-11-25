-- PostgreSQL 14+. Dated windows use absolute instants; local preferences use
-- the hospital's IANA time zone. Every interval is finite and half-open [start,end).
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;
CREATE SCHEMA hospital;
SET LOCAL search_path = hospital, public;

CREATE TABLE demo_dataset (
    dataset_key text PRIMARY KEY,
    description text NOT NULL,
    loaded_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE TABLE hospital (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    time_zone text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE department (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    hospital_id bigint NOT NULL REFERENCES hospital(id),
    name text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE specialty (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    department_id bigint NOT NULL REFERENCES department(id),
    name text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE doctor (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    department_id bigint NOT NULL REFERENCES department(id),
    display_name text NOT NULL,
    email text UNIQUE,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE doctor_specialty (
    doctor_id bigint NOT NULL REFERENCES doctor(id),
    specialty_id bigint NOT NULL REFERENCES specialty(id),
    PRIMARY KEY (doctor_id, specialty_id)
);

CREATE TABLE room_type (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE room (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    department_id bigint NOT NULL REFERENCES department(id),
    room_type_id bigint NOT NULL REFERENCES room_type(id),
    name text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE equipment (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE room_equipment (
    room_id bigint NOT NULL REFERENCES room(id),
    equipment_id bigint NOT NULL REFERENCES equipment(id),
    quantity integer NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (room_id, equipment_id)
);

CREATE TABLE patient (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    display_name text NOT NULL,
    email text UNIQUE,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE doctor_availability (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doctor_id bigint NOT NULL REFERENCES doctor(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    occupied_at tstzrange GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    CONSTRAINT doctor_availability_bounds CHECK
        (isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at),
    UNIQUE (doctor_id, starts_at, ends_at)
);

CREATE TABLE doctor_absence (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doctor_id bigint NOT NULL REFERENCES doctor(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    reason text NOT NULL,
    occupied_at tstzrange GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    CONSTRAINT doctor_absence_bounds CHECK
        (isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at),
    UNIQUE (doctor_id, starts_at, ends_at)
);

CREATE TABLE doctor_break (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doctor_id bigint NOT NULL REFERENCES doctor(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    label text NOT NULL,
    occupied_at tstzrange GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    CONSTRAINT doctor_break_bounds CHECK
        (isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at),
    UNIQUE (doctor_id, starts_at, ends_at)
);

CREATE TABLE patient_availability (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id bigint NOT NULL REFERENCES patient(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    occupied_at tstzrange GENERATED ALWAYS AS (tstzrange(starts_at, ends_at, '[)')) STORED,
    CONSTRAINT patient_availability_bounds CHECK
        (isfinite(starts_at) AND isfinite(ends_at) AND ends_at > starts_at),
    UNIQUE (patient_id, starts_at, ends_at),
    UNIQUE (id, patient_id)
);

CREATE TABLE patient_time_preference (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id bigint NOT NULL REFERENCES patient(id),
    iso_weekday smallint NOT NULL CHECK (iso_weekday BETWEEN 1 AND 7),
    local_start time NOT NULL,
    local_end time NOT NULL,
    weight integer NOT NULL CHECK (weight > 0),
    CHECK (local_end > local_start),
    UNIQUE (patient_id, iso_weekday, local_start, local_end)
);

CREATE TABLE appointment_request (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    patient_id bigint NOT NULL REFERENCES patient(id),
    specialty_id bigint NOT NULL REFERENCES specialty(id),
    required_room_type_id bigint REFERENCES room_type(id),
    duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 1440),
    urgency text NOT NULL CHECK (urgency IN ('URGENT', 'PRIORITY', 'ROUTINE')),
    requested_at timestamptz NOT NULL CHECK (isfinite(requested_at)),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CANCELLED', 'CLOSED')),
    demo_dataset_key text REFERENCES demo_dataset(dataset_key),
    UNIQUE (id, patient_id)
);

-- The two composite FKs prevent a request from using another patient's window.
CREATE TABLE request_availability (
    request_id bigint NOT NULL,
    patient_id bigint NOT NULL,
    patient_availability_id bigint NOT NULL,
    PRIMARY KEY (request_id, patient_availability_id),
    FOREIGN KEY (request_id, patient_id) REFERENCES appointment_request(id, patient_id),
    FOREIGN KEY (patient_availability_id, patient_id) REFERENCES patient_availability(id, patient_id)
);

CREATE TABLE request_equipment (
    request_id bigint NOT NULL REFERENCES appointment_request(id),
    equipment_id bigint NOT NULL REFERENCES equipment(id),
    quantity integer NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (request_id, equipment_id)
);

CREATE INDEX department_hospital_idx ON department(hospital_id);
CREATE INDEX specialty_department_idx ON specialty(department_id);
CREATE INDEX doctor_department_idx ON doctor(department_id);
CREATE INDEX doctor_specialty_specialty_idx ON doctor_specialty(specialty_id);
CREATE INDEX room_department_idx ON room(department_id);
CREATE INDEX room_type_idx ON room(room_type_id);
CREATE INDEX room_equipment_equipment_idx ON room_equipment(equipment_id);
CREATE INDEX doctor_availability_range_idx ON doctor_availability USING gist (doctor_id, occupied_at);
CREATE INDEX doctor_absence_range_idx ON doctor_absence USING gist (doctor_id, occupied_at);
CREATE INDEX doctor_break_range_idx ON doctor_break USING gist (doctor_id, occupied_at);
CREATE INDEX patient_availability_range_idx ON patient_availability USING gist (patient_id, occupied_at);
CREATE INDEX request_patient_idx ON appointment_request(patient_id);
CREATE INDEX request_specialty_urgency_idx ON appointment_request(specialty_id, urgency, requested_at);
CREATE INDEX request_room_type_idx ON appointment_request(required_room_type_id);
CREATE INDEX request_availability_window_idx ON request_availability(patient_availability_id);
CREATE INDEX request_equipment_equipment_idx ON request_equipment(equipment_id);
