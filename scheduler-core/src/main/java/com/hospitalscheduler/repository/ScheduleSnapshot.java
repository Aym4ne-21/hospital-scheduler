package com.hospitalscheduler.repository;

import com.hospitalscheduler.model.Schedule;
import com.hospitalscheduler.optimization.FitnessBreakdown;
import com.hospitalscheduler.optimization.OptimizationResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable candidate data. SQL validates parametersJson as a JSON object. */
public record ScheduleSnapshot(String code, String hospitalCode, LocalDate startsOn,
                               Algorithm algorithm, String parametersJson, long randomSeed,
                               FitnessBreakdown finalFitness, List<Assignment> assignments,
                               List<FitnessBreakdown> history, String optimizerStatus) {
    public ScheduleSnapshot {
        requireText(code, "Schedule code");
        requireText(hospitalCode, "Hospital code");
        HospitalWeek.requireMonday(startsOn);
        Objects.requireNonNull(algorithm);
        Objects.requireNonNull(parametersJson);
        history = List.copyOf(history);
        assignments = List.copyOf(assignments);
        Set<Long> requests = new HashSet<>();
        for (Assignment assignment : assignments) {
            if (!requests.add(assignment.requestId())) {
                throw new IllegalArgumentException("Duplicate request ID: " + assignment.requestId());
            }
        }
    }

    public ScheduleSnapshot(String code, String hospitalCode, LocalDate startsOn, Algorithm algorithm,
                            String parametersJson, long randomSeed, FitnessBreakdown finalFitness,
                            List<Assignment> assignments) {
        this(code, hospitalCode, startsOn, algorithm, parametersJson, randomSeed, finalFitness,
                assignments, List.of(), null);
    }

    public record RunMetadata(String code, String hospitalCode, LocalDate startsOn, Algorithm algorithm,
                              String parametersJson, long randomSeed) {
        public RunMetadata {
            requireText(code, "Schedule code");
            requireText(hospitalCode, "Hospital code");
            HospitalWeek.requireMonday(startsOn);
            Objects.requireNonNull(algorithm);
            Objects.requireNonNull(parametersJson);
        }
    }

    public record StoredResult(Stored stored, OptimizationResult result, Set<Long> lockedRequestIds) {
        public StoredResult {
            Objects.requireNonNull(stored);
            Objects.requireNonNull(result);
            lockedRequestIds = Set.copyOf(lockedRequestIds);
        }
        public Schedule schedule() { return result.bestSchedule(); }
    }

    public record Algorithm(String code, String name, String version) {
        public Algorithm {
            requireText(code, "Algorithm code");
            requireText(name, "Algorithm name");
            requireText(version, "Algorithm version");
        }
    }

    public record Assignment(long requestId, long doctorId, long roomId,
                             Instant startsAt, Instant endsAt, boolean locked) {
        public Assignment {
            if (requestId <= 0 || doctorId <= 0 || roomId <= 0) {
                throw new IllegalArgumentException("Assignment references must be positive IDs");
            }
            // Uses the same duration and timestamp-precision contract as hospital data.
            new HospitalWeek.Window(startsAt, endsAt);
        }
    }

    public enum Status { DRAFT, RUNNING, CANDIDATE, CONFIRMED, ARCHIVED }

    public record Stored(long id, ScheduleSnapshot snapshot, Status status, Instant createdAt, BigDecimal legacyFitness) {
        public Stored(long id, ScheduleSnapshot snapshot, Status status, Instant createdAt) {
            this(id, snapshot, status, createdAt, null);
        }
        public Stored {
            if (id <= 0) throw new IllegalArgumentException("Stored schedule ID must be positive");
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(status);
            Objects.requireNonNull(createdAt);
            if ((status == Status.CANDIDATE || status == Status.CONFIRMED) && snapshot.finalFitness() == null && legacyFitness == null) {
                throw new IllegalArgumentException("Completed schedules must have a fitness value");
            }
        }
    }

    private static void requireText(String value, String label) {
        if (Objects.requireNonNull(value).isBlank()) throw new IllegalArgumentException(label + " cannot be blank");
    }
}
