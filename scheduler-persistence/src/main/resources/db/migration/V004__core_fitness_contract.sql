SET LOCAL search_path = hospital, public;

-- Preserve the two independent components used by FitnessBreakdown.
-- The legacy scalar remains available for inspection; its components cannot be inferred.
ALTER TABLE schedule
    ADD COLUMN hard_violations integer CHECK (hard_violations >= 0),
    ADD COLUMN soft_cost double precision CHECK (soft_cost >= 0 AND soft_cost < 'Infinity'::double precision),
    ADD COLUMN optimizer_status text,
    ADD COLUMN time_zone text,
    ADD CONSTRAINT schedule_fitness_pair CHECK
        ((hard_violations IS NULL) = (soft_cost IS NULL)),
    DROP CONSTRAINT schedule_final_result,
    ADD CONSTRAINT schedule_final_result CHECK
        (status NOT IN ('CANDIDATE', 'CONFIRMED') OR final_fitness IS NOT NULL
         OR (hard_violations IS NOT NULL AND soft_cost IS NOT NULL));

CREATE TABLE schedule_fitness_history (
    schedule_id bigint NOT NULL REFERENCES schedule(id),
    iteration integer NOT NULL CHECK (iteration >= 0),
    hard_violations integer NOT NULL CHECK (hard_violations >= 0),
    soft_cost double precision NOT NULL CHECK (soft_cost >= 0 AND soft_cost < 'Infinity'::double precision),
    PRIMARY KEY (schedule_id, iteration)
);

COMMENT ON COLUMN schedule.final_fitness IS
    'Legacy scalar retained without guessing hard violations or soft cost. New repository writes use the two component columns.';
COMMENT ON COLUMN schedule.optimizer_status IS
    'OptimizationResult status, independent of the saved schedule lifecycle status.';
COMMENT ON COLUMN schedule_fitness_history.iteration IS
    'Zero-based position in OptimizationResult.history; retains every entry in order.';

-- Capture the conversion context of pre-existing versions using the current hospital zone.
UPDATE schedule s SET time_zone=h.time_zone
FROM schedule_week w JOIN hospital h ON h.id=w.hospital_id
WHERE s.schedule_week_id=w.id;
COMMENT ON COLUMN schedule.time_zone IS
    'Hospital ZoneId captured at save time for stable shared-core LocalDateTime conversion.';
