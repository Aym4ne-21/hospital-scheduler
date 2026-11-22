SET LOCAL search_path = hospital, public;

-- Protect operational reservations globally, including across schedule versions.
-- btree_gist was installed by V001; occupied_at is the [start,end) range from V002.
ALTER TABLE confirmed_appointment
    ADD CONSTRAINT confirmed_doctor_no_overlap
        EXCLUDE USING gist (doctor_id WITH =, occupied_at WITH &&),
    ADD CONSTRAINT confirmed_room_no_overlap
        EXCLUDE USING gist (room_id WITH =, occupied_at WITH &&);

COMMENT ON TABLE confirmed_appointment IS
    'Operational reservations referencing exact saved assignments. Global doctor and room exclusions reject overlaps, including concurrent confirmations; adjacent appointments are allowed.';
