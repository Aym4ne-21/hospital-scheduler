package com.hospitalscheduler.persistence;

import com.hospitalscheduler.repository.HospitalWeek;
import com.hospitalscheduler.model.Schedule;
import com.hospitalscheduler.optimization.FitnessBreakdown;
import com.hospitalscheduler.optimization.OptimizationProblem;
import com.hospitalscheduler.optimization.OptimizationResult;
import com.hospitalscheduler.repository.ScheduleRepository;
import com.hospitalscheduler.repository.ScheduleRepositoryException;
import com.hospitalscheduler.repository.ScheduleSnapshot;
import com.hospitalscheduler.repository.ScheduleSnapshot.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Saves immutable candidate versions; operational confirmation is a separate operation. */
public final class ScheduleRepositoryJdbc implements ScheduleRepository {
    private final DataSource dataSource;
    private final CoreModelMapper mapper;

    private static final String METADATA = """
            SELECT s.id,s.code,s.random_seed,s.final_fitness,s.hard_violations,s.soft_cost,s.optimizer_status,s.status,s.created_at,
                   s.parameters::text parameters_json,h.code hospital_code,w.starts_on,
                   alg.code algorithm_code,alg.name algorithm_name,alg.version algorithm_version
            FROM hospital.schedule s JOIN hospital.schedule_week w ON w.id=s.schedule_week_id
            JOIN hospital.hospital h ON h.id=w.hospital_id
            JOIN hospital.algorithm alg ON alg.id=s.algorithm_id
            """;

    private static final String ASSIGNMENTS = """
            SELECT a.schedule_id,a.request_id,a.doctor_id,a.room_id,a.starts_at,a.ends_at,a.locked
            FROM hospital.candidate_assignment a JOIN hospital.schedule s ON s.id=a.schedule_id
            JOIN hospital.schedule_week w ON w.id=s.schedule_week_id
            JOIN hospital.hospital h ON h.id=w.hospital_id
            """;

    public ScheduleRepositoryJdbc(DataSource dataSource) {
        this(dataSource, new CoreModelMapper());
    }

    public ScheduleRepositoryJdbc(DataSource dataSource, CoreModelMapper mapper) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public StoredResult saveResult(RunMetadata metadata, OptimizationResult result, Set<Long> lockedRequestIds) {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(result);
        Set<Long> locks = Set.copyOf(lockedRequestIds);
        return transaction(false, c -> {
            ZoneId zone = hospitalZone(c, metadata.hospitalCode());
            ScheduleSnapshot snapshot = new ScheduleSnapshot(metadata.code(), metadata.hospitalCode(), metadata.startsOn(),
                    metadata.algorithm(), metadata.parametersJson(), metadata.randomSeed(), result.bestFitness(),
                    mapper.toAssignments(result.bestSchedule(), zone, locks), result.history(), result.status());
            return new StoredResult(saveCandidate(c, snapshot, zone), result, locks);
        });
    }

    @Override
    public Optional<StoredResult> loadResult(String scheduleCode, OptimizationProblem problem) {
        Objects.requireNonNull(scheduleCode);
        Objects.requireNonNull(problem);
        return transaction(true, c -> {
            List<Stored> matches = read(c, "s.code=?", scheduleCode);
            if (matches.isEmpty()) return Optional.empty();
            Stored stored = matches.get(0);
            ScheduleSnapshot snapshot = stored.snapshot();
            if (snapshot.finalFitness() == null || snapshot.optimizerStatus() == null) {
                throw new IllegalStateException("This version has no complete OptimizationResult; legacy scalar fitness cannot be split automatically");
            }
            Schedule schedule = mapper.toSchedule(snapshot, problem, scheduleZone(c, scheduleCode));
            Set<Long> locks = new HashSet<>();
            snapshot.assignments().stream().filter(Assignment::locked).forEach(a -> locks.add(a.requestId()));
            return Optional.of(new StoredResult(stored,
                    new OptimizationResult(schedule, snapshot.finalFitness(), snapshot.history(), snapshot.optimizerStatus()), locks));
        });
    }

    @Override
    public Optional<Schedule> loadSchedule(String scheduleCode, OptimizationProblem problem) {
        Objects.requireNonNull(scheduleCode);
        Objects.requireNonNull(problem);
        return transaction(true, c -> {
            List<Stored> matches = read(c, "s.code=?", scheduleCode);
            return matches.isEmpty() ? Optional.empty() : Optional.of(
                    mapper.toSchedule(matches.get(0).snapshot(), problem, scheduleZone(c, scheduleCode)));
        });
    }

    @Override
    public Stored saveCandidate(ScheduleSnapshot candidate) {
        Objects.requireNonNull(candidate);
        if (candidate.finalFitness() == null) throw new IllegalArgumentException("Candidate fitness is required");
        return transaction(false, c -> saveCandidate(c, candidate, hospitalZone(c, candidate.hospitalCode())));
    }

    private Stored saveCandidate(Connection c, ScheduleSnapshot candidate, ZoneId zone) throws SQLException {
        long hospitalId;
        try (var s = prepare(c, "SELECT id FROM hospital.hospital WHERE code=?", candidate.hospitalCode());
             var r = s.executeQuery()) {
            if (!r.next()) throw new IllegalArgumentException("Unknown hospital: " + candidate.hospitalCode());
            hospitalId = r.getLong(1);
        }
        long weekId = returningId(c, """
                INSERT INTO hospital.schedule_week(code,hospital_id,starts_on) VALUES (?,?,?)
                ON CONFLICT (hospital_id,starts_on) DO UPDATE SET hospital_id=EXCLUDED.hospital_id RETURNING id
                """, "week." + UUID.randomUUID(), hospitalId, candidate.startsOn());
        Algorithm algorithm = candidate.algorithm();
        long algorithmId = returningId(c, """
                INSERT INTO hospital.algorithm(code,name,version) VALUES (?,?,?)
                ON CONFLICT (code) DO UPDATE SET code=EXCLUDED.code
                WHERE algorithm.name=EXCLUDED.name AND algorithm.version=EXCLUDED.version RETURNING id
                """, algorithm.code(), algorithm.name(), algorithm.version());
        long scheduleId = returningId(c, """
                INSERT INTO hospital.schedule
                    (code,schedule_week_id,algorithm_id,parameters,random_seed,hard_violations,soft_cost,optimizer_status,time_zone,status)
                VALUES (?,?,?,?::jsonb,?,?,?,?,?,'CANDIDATE') RETURNING id
                """, candidate.code(), weekId, algorithmId, candidate.parametersJson(),
                candidate.randomSeed(), candidate.finalFitness().hardViolations(), candidate.finalFitness().softCost(),
                candidate.optimizerStatus(), zone.getId());
        saveAssignments(c, scheduleId, hospitalId, candidate.assignments());
        saveHistory(c, scheduleId, candidate.history());
        // Read back canonical JSON, database timestamps, IDs and deterministic assignment order.
        return read(c, "s.code=?", candidate.code()).get(0);
    }

    @Override
    public Optional<Stored> load(String scheduleCode) {
        Objects.requireNonNull(scheduleCode);
        return transaction(true, c -> read(c, "s.code=?", scheduleCode).stream().findFirst());
    }

    @Override
    public List<Stored> loadWeek(String hospitalCode, LocalDate startsOn) {
        Objects.requireNonNull(hospitalCode);
        HospitalWeek.requireMonday(startsOn);
        return transaction(true, c -> read(c, "h.code=? AND w.starts_on=?", hospitalCode, startsOn));
    }

    private static void saveAssignments(Connection c, long scheduleId, long hospitalId,
                                        List<Assignment> assignments) throws SQLException {
        if (assignments.isEmpty()) return;
        // Each SELECT checks that all three referenced entities belong to this hospital.
        // A zero row count is invalid input and rolls back the whole candidate save.
        try (var s = c.prepareStatement("""
                INSERT INTO hospital.candidate_assignment
                    (schedule_id,request_id,doctor_id,room_id,starts_at,ends_at,locked)
                SELECT ?,req.id,dr.id,rm.id,?,?,?
                FROM hospital.appointment_request req
                JOIN hospital.specialty sp ON sp.id=req.specialty_id
                JOIN hospital.department rd ON rd.id=sp.department_id
                JOIN hospital.doctor dr ON dr.id=? JOIN hospital.department dd ON dd.id=dr.department_id
                JOIN hospital.room rm ON rm.id=? JOIN hospital.department md ON md.id=rm.department_id
                WHERE req.id=? AND rd.hospital_id=? AND dd.hospital_id=? AND md.hospital_id=?
                """)) {
            for (Assignment a : assignments) {
                bind(s, scheduleId, a.startsAt(), a.endsAt(), a.locked(), a.doctorId(), a.roomId(), a.requestId(),
                        hospitalId, hospitalId, hospitalId);
                s.addBatch();
            }
            int[] counts = s.executeBatch();
            if (counts.length != assignments.size()) throw new SQLException("Incomplete assignment batch result");
            for (int count : counts) {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                    throw new IllegalArgumentException("Assignment references are unknown or belong to another hospital");
                }
            }
        }
    }

    private static List<Stored> read(Connection c, String scope, Object... args) throws SQLException {
        Map<Long, List<Assignment>> assignments = new HashMap<>();
        // Scope strings are internal constants. All user values are bound parameters.
        try (var s = prepare(c, ASSIGNMENTS + " WHERE " + scope + " ORDER BY a.schedule_id,a.request_id", args);
             var r = s.executeQuery()) {
            while (r.next()) {
                assignments.computeIfAbsent(r.getLong("schedule_id"), ignored -> new ArrayList<>()).add(
                        new Assignment(r.getLong("request_id"), r.getLong("doctor_id"), r.getLong("room_id"),
                                instant(r, "starts_at"), instant(r, "ends_at"), r.getBoolean("locked")));
            }
        }
        Map<Long, List<FitnessBreakdown>> history = new HashMap<>();
        try (var s = prepare(c, """
                SELECT f.schedule_id,f.hard_violations,f.soft_cost FROM hospital.schedule_fitness_history f
                JOIN hospital.schedule s ON s.id=f.schedule_id
                JOIN hospital.schedule_week w ON w.id=s.schedule_week_id
                JOIN hospital.hospital h ON h.id=w.hospital_id
                """ + " WHERE " + scope + " ORDER BY f.schedule_id,f.iteration", args);
             var r = s.executeQuery()) {
            while (r.next()) history.computeIfAbsent(r.getLong("schedule_id"), ignored -> new ArrayList<>()).add(fitness(r));
        }
        List<Stored> schedules = new ArrayList<>();
        try (var s = prepare(c, METADATA + " WHERE " + scope + " ORDER BY s.created_at,s.id", args);
             var r = s.executeQuery()) {
            while (r.next()) {
                long id = r.getLong("id");
                ScheduleSnapshot snapshot = new ScheduleSnapshot(r.getString("code"), r.getString("hospital_code"),
                        r.getObject("starts_on", LocalDate.class),
                        new Algorithm(r.getString("algorithm_code"), r.getString("algorithm_name"), r.getString("algorithm_version")),
                        r.getString("parameters_json"), r.getLong("random_seed"), fitness(r),
                        assignments.getOrDefault(id, List.of()), history.getOrDefault(id, List.of()), r.getString("optimizer_status"));
                schedules.add(new Stored(id, snapshot, Status.valueOf(r.getString("status")), instant(r, "created_at"),
                        r.getBigDecimal("final_fitness")));
            }
        }
        return List.copyOf(schedules);
    }

    private static FitnessBreakdown fitness(ResultSet r) throws SQLException {
        Integer hard = r.getObject("hard_violations", Integer.class);
        return hard == null ? null : new FitnessBreakdown(hard, r.getDouble("soft_cost"));
    }

    private static void saveHistory(Connection c, long scheduleId, List<FitnessBreakdown> history) throws SQLException {
        if (history.isEmpty()) return;
        try (var s = c.prepareStatement("""
                INSERT INTO hospital.schedule_fitness_history(schedule_id,iteration,hard_violations,soft_cost) VALUES (?,?,?,?)
                """)) {
            for (int i = 0; i < history.size(); i++) {
                FitnessBreakdown score = history.get(i);
                bind(s, scheduleId, i, score.hardViolations(), score.softCost());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static ZoneId hospitalZone(Connection c, String code) throws SQLException {
        try (var s = prepare(c, "SELECT time_zone FROM hospital.hospital WHERE code=?", code); var r = s.executeQuery()) {
            if (!r.next()) throw new IllegalArgumentException("Unknown hospital: " + code);
            return ZoneId.of(r.getString(1));
        }
    }

    private static ZoneId scheduleZone(Connection c, String code) throws SQLException {
        try (var s = prepare(c, """
                SELECT coalesce(s.time_zone,h.time_zone) FROM hospital.schedule s
                JOIN hospital.schedule_week w ON w.id=s.schedule_week_id
                JOIN hospital.hospital h ON h.id=w.hospital_id WHERE s.code=?
                """, code); var r = s.executeQuery()) {
            if (!r.next()) throw new IllegalArgumentException("Unknown schedule: " + code);
            return ZoneId.of(r.getString(1));
        }
    }

    private <T> T transaction(boolean readOnly, SqlWork<T> work) {
        try (var c = dataSource.getConnection()) {
            c.setTransactionIsolation(readOnly ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
            c.setReadOnly(readOnly);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try { c.rollback(); } catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
        } catch (SQLException error) {
            throw new ScheduleRepositoryException("Could not " + (readOnly ? "load" : "save") + " schedule", error);
        }
    }

    private static long returningId(Connection c, String sql, Object... args) throws SQLException {
        try (var s = prepare(c, sql, args); var r = s.executeQuery()) {
            if (!r.next()) throw new IllegalArgumentException("Algorithm code already identifies a different name or version");
            return r.getLong(1);
        }
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        return r.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        try {
            bind(statement, args);
            return statement;
        } catch (SQLException | RuntimeException error) {
            try { statement.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            s.setObject(i + 1, value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
        }
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
}
