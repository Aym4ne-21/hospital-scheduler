package com.hospitalscheduler.persistence;

import com.hospitalscheduler.repository.HospitalRepository;
import com.hospitalscheduler.repository.HospitalRepositoryException;
import com.hospitalscheduler.repository.HospitalWeek;
import com.hospitalscheduler.repository.HospitalWeek.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** JDBC adapter. Each call owns one connection and one transaction. */
public final class HospitalRepositoryJdbc implements HospitalRepository {
    private final DataSource dataSource;

    // Windowless requests remain visible so validation can explain missing availability.
    private static final String WEEK_REQUESTS = """
            WITH week_requests AS (
                SELECT r.* FROM hospital.appointment_request r
                JOIN hospital.specialty s ON s.id=r.specialty_id
                JOIN hospital.department d ON d.id=s.department_id
                WHERE d.hospital_id=? AND (
                    NOT EXISTS (SELECT 1 FROM hospital.request_availability a WHERE a.request_id=r.id)
                    OR EXISTS (SELECT 1 FROM hospital.request_availability a
                        JOIN hospital.patient_availability w ON w.id=a.patient_availability_id
                        WHERE a.request_id=r.id AND w.starts_at<? AND w.ends_at>?)
                )
            )
            """;

    public HospitalRepositoryJdbc(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public Optional<HospitalWeek> loadWeek(String hospitalCode, LocalDate startsOn) {
        Objects.requireNonNull(hospitalCode);
        HospitalWeek.requireMonday(startsOn);
        return transaction(true, connection -> {
            List<Hospital> hospitals = query(connection,
                    "SELECT id,code,name,time_zone FROM hospital.hospital WHERE code=?",
                    r -> new Hospital(r.getLong("id"), r.getString("code"), r.getString("name"),
                            ZoneId.of(r.getString("time_zone"))), hospitalCode);
            if (hospitals.isEmpty()) return Optional.empty();
            Hospital hospital = hospitals.get(0);
            Instant start = startsOn.atStartOfDay(hospital.timeZone()).toInstant();
            Instant end = startsOn.plusWeeks(1).atStartOfDay(hospital.timeZone()).toInstant();
            long id = hospital.id();
            Object[] scope = {id, end, start};
            List<Department> departments = query(connection,
                    "SELECT id,code,name FROM hospital.department WHERE hospital_id=? ORDER BY id",
                    r -> new Department(r.getLong("id"), r.getString("code"), r.getString("name")), id);
            List<Specialty> specialties = query(connection, """
                    SELECT s.id,s.code,s.name,d.code department_code FROM hospital.specialty s
                    JOIN hospital.department d ON d.id=s.department_id WHERE d.hospital_id=? ORDER BY s.id
                    """, r -> new Specialty(r.getLong("id"), r.getString("code"),
                    r.getString("department_code"), r.getString("name")), id);
            Map<Long, List<String>> doctorSpecialties = grouped(connection, """
                    SELECT ds.doctor_id owner_id,s.code FROM hospital.doctor_specialty ds
                    JOIN hospital.doctor dr ON dr.id=ds.doctor_id
                    JOIN hospital.department d ON d.id=dr.department_id
                    JOIN hospital.specialty s ON s.id=ds.specialty_id
                    WHERE d.hospital_id=? ORDER BY ds.doctor_id,s.id
                    """, r -> r.getString("code"), id);
            Map<Long, List<Window>> doctorAvailability = doctorWindows(connection,
                    "doctor_availability", HospitalRepositoryJdbc::window, scope);
            Map<Long, List<Absence>> absences = doctorWindows(connection,
                    "doctor_absence", r -> new Absence(window(r), r.getString("reason")), scope);
            Map<Long, List<DoctorBreak>> breaks = doctorWindows(connection,
                    "doctor_break", r -> new DoctorBreak(window(r), r.getString("label")), scope);
            List<Doctor> doctors = query(connection, """
                    SELECT dr.*,d.code department_code FROM hospital.doctor dr
                    JOIN hospital.department d ON d.id=dr.department_id WHERE d.hospital_id=? ORDER BY dr.id
                    """, r -> {
                long doctorId = r.getLong("id");
                return new Doctor(doctorId, r.getString("code"), r.getString("department_code"),
                        r.getString("display_name"), r.getString("email"),
                        values(doctorSpecialties, doctorId), values(doctorAvailability, doctorId),
                        values(absences, doctorId), values(breaks, doctorId));
            }, id);
            List<RoomType> roomTypes = query(connection, "SELECT * FROM hospital.room_type ORDER BY id",
                    r -> new RoomType(r.getLong("id"), r.getString("code"), r.getString("name")));
            List<Equipment> equipment = query(connection, "SELECT * FROM hospital.equipment ORDER BY id",
                    r -> new Equipment(r.getLong("id"), r.getString("code"), r.getString("name")));
            Map<Long, List<EquipmentQuantity>> roomEquipment = grouped(connection, """
                    SELECT re.room_id owner_id,e.code,re.quantity FROM hospital.room_equipment re
                    JOIN hospital.room rm ON rm.id=re.room_id
                    JOIN hospital.department d ON d.id=rm.department_id
                    JOIN hospital.equipment e ON e.id=re.equipment_id
                    WHERE d.hospital_id=? ORDER BY re.room_id,e.id
                    """, HospitalRepositoryJdbc::equipmentQuantity, id);
            List<Room> rooms = query(connection, """
                    SELECT rm.*,d.code department_code,t.code room_type_code FROM hospital.room rm
                    JOIN hospital.department d ON d.id=rm.department_id
                    JOIN hospital.room_type t ON t.id=rm.room_type_id WHERE d.hospital_id=? ORDER BY rm.id
                    """, r -> new Room(r.getLong("id"), r.getString("code"), r.getString("department_code"),
                    r.getString("room_type_code"), r.getString("name"), values(roomEquipment, r.getLong("id"))), id);
            Map<Long, List<Window>> patientAvailability = grouped(connection, WEEK_REQUESTS + """
                    SELECT w.*,w.patient_id owner_id FROM hospital.patient_availability w
                    WHERE w.patient_id IN (SELECT patient_id FROM week_requests)
                    AND w.starts_at<? AND w.ends_at>? ORDER BY w.patient_id,w.starts_at,w.ends_at
                    """, HospitalRepositoryJdbc::window, id, end, start, end, start);
            Map<Long, List<Preference>> preferences = grouped(connection, WEEK_REQUESTS + """
                    SELECT p.*,p.patient_id owner_id FROM hospital.patient_time_preference p
                    WHERE p.patient_id IN (SELECT patient_id FROM week_requests)
                    ORDER BY p.patient_id,p.iso_weekday,p.local_start,p.local_end
                    """, r -> new Preference(DayOfWeek.of(r.getInt("iso_weekday")),
                    r.getObject("local_start", LocalTime.class), r.getObject("local_end", LocalTime.class),
                    r.getInt("weight")), scope);
            List<Patient> patients = query(connection, WEEK_REQUESTS + """
                    SELECT p.* FROM hospital.patient p
                    WHERE p.id IN (SELECT patient_id FROM week_requests) ORDER BY p.id
                    """, r -> new Patient(r.getLong("id"), r.getString("code"), r.getString("display_name"),
                    r.getString("email"), values(patientAvailability, r.getLong("id")),
                    values(preferences, r.getLong("id"))), scope);
            Map<Long, List<Window>> requestAvailability = grouped(connection, WEEK_REQUESTS + """
                    SELECT a.request_id owner_id,w.starts_at,w.ends_at FROM hospital.request_availability a
                    JOIN hospital.patient_availability w ON w.id=a.patient_availability_id
                    WHERE a.request_id IN (SELECT id FROM week_requests) AND w.starts_at<? AND w.ends_at>?
                    ORDER BY a.request_id,w.starts_at,w.ends_at
                    """, HospitalRepositoryJdbc::window, id, end, start, end, start);
            Map<Long, List<EquipmentQuantity>> requestEquipment = grouped(connection, WEEK_REQUESTS + """
                    SELECT re.request_id owner_id,e.code,re.quantity FROM hospital.request_equipment re
                    JOIN hospital.equipment e ON e.id=re.equipment_id
                    WHERE re.request_id IN (SELECT id FROM week_requests) ORDER BY re.request_id,e.id
                    """, HospitalRepositoryJdbc::equipmentQuantity, scope);
            List<Request> requests = query(connection, WEEK_REQUESTS + """
                    SELECT r.*,p.code patient_code,s.code specialty_code,t.code room_type_code
                    FROM week_requests r JOIN hospital.patient p ON p.id=r.patient_id
                    JOIN hospital.specialty s ON s.id=r.specialty_id
                    LEFT JOIN hospital.room_type t ON t.id=r.required_room_type_id ORDER BY r.id
                    """, r -> new Request(r.getLong("id"), r.getString("code"), r.getString("patient_code"),
                    r.getString("specialty_code"), r.getString("room_type_code"), r.getInt("duration_minutes"),
                    Urgency.valueOf(r.getString("urgency")), instant(r, "requested_at"),
                    RequestStatus.valueOf(r.getString("status")), values(requestAvailability, r.getLong("id")),
                    values(requestEquipment, r.getLong("id"))), scope);
            return Optional.of(new HospitalWeek(hospital, startsOn, departments, specialties, doctors,
                    roomTypes, equipment, rooms, patients, requests));
        });
    }

    @Override
    public void saveWeek(HospitalWeek week) {
        Objects.requireNonNull(week);
        transaction(false, c -> {
            Hospital h = week.hospital();
            long hospitalId = upsert(c, h.id(), """
                    INSERT INTO hospital.hospital(code,name,time_zone) VALUES (?,?,?)
                    ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,time_zone=EXCLUDED.time_zone RETURNING id
                    """, h.code(), h.name(), h.timeZone().getId());
            // The upsert locks this hospital until commit, serializing saves through this adapter.
            Map<String, Long> departments = ids(c,
                    "SELECT code,id FROM hospital.department WHERE hospital_id=?", hospitalId);
            for (Department d : week.departments()) {
                departments.put(d.code(), upsert(c, d.id(), """
                        INSERT INTO hospital.department(code,hospital_id,name) VALUES (?,?,?)
                        ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name
                        WHERE department.hospital_id=EXCLUDED.hospital_id RETURNING id
                        """, d.code(), hospitalId, d.name()));
            }
            Map<String, Long> specialties = ids(c, """
                    SELECT s.code,s.id FROM hospital.specialty s JOIN hospital.department d ON d.id=s.department_id
                    WHERE d.hospital_id=?
                    """, hospitalId);
            for (Specialty s : week.specialties()) {
                specialties.put(s.code(), upsert(c, s.id(), """
                        INSERT INTO hospital.specialty(code,department_id,name) VALUES (?,?,?)
                        ON CONFLICT (code) DO UPDATE SET department_id=EXCLUDED.department_id,name=EXCLUDED.name
                        WHERE specialty.department_id IN (SELECT id FROM hospital.department WHERE hospital_id=?)
                        RETURNING id
                        """, s.code(), reference(departments, s.departmentCode()), s.name(), hospitalId));
            }
            Map<String, Long> roomTypes = ids(c, "SELECT code,id FROM hospital.room_type");
            for (RoomType t : week.roomTypes()) {
                roomTypes.put(t.code(), upsert(c, t.id(), """
                        INSERT INTO hospital.room_type(code,name) VALUES (?,?)
                        ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name RETURNING id
                        """, t.code(), t.name()));
            }
            Map<String, Long> equipment = ids(c, "SELECT code,id FROM hospital.equipment");
            for (Equipment e : week.equipment()) {
                equipment.put(e.code(), upsert(c, e.id(), """
                        INSERT INTO hospital.equipment(code,name) VALUES (?,?)
                        ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name RETURNING id
                        """, e.code(), e.name()));
            }
            for (Doctor d : week.doctors()) {
                long doctorId = upsert(c, d.id(), """
                        INSERT INTO hospital.doctor(code,department_id,display_name,email) VALUES (?,?,?,?)
                        ON CONFLICT (code) DO UPDATE SET department_id=EXCLUDED.department_id,
                        display_name=EXCLUDED.display_name,email=EXCLUDED.email
                        WHERE doctor.department_id IN (SELECT id FROM hospital.department WHERE hospital_id=?)
                        RETURNING id
                        """, d.code(), reference(departments, d.departmentCode()), d.displayName(), d.email(), hospitalId);
                execute(c, "DELETE FROM hospital.doctor_specialty WHERE doctor_id=?", doctorId);
                List<Object[]> qualifications = new ArrayList<>();
                for (String specialty : d.specialtyCodes()) {
                    qualifications.add(new Object[]{doctorId, reference(specialties, specialty)});
                }
                batch(c, "INSERT INTO hospital.doctor_specialty(doctor_id,specialty_id) VALUES (?,?)", qualifications);
                replaceDoctorWindows(c, week, doctorId, "doctor_availability", null, d.availability().stream()
                        .map(w -> new DatedEntry(w, null)).toList());
                replaceDoctorWindows(c, week, doctorId, "doctor_absence", "reason", d.absences().stream()
                        .map(a -> new DatedEntry(a.window(), a.reason())).toList());
                replaceDoctorWindows(c, week, doctorId, "doctor_break", "label", d.breaks().stream()
                        .map(b -> new DatedEntry(b.window(), b.label())).toList());
            }
            for (Room r : week.rooms()) {
                long roomId = upsert(c, r.id(), """
                        INSERT INTO hospital.room(code,department_id,room_type_id,name) VALUES (?,?,?,?)
                        ON CONFLICT (code) DO UPDATE SET department_id=EXCLUDED.department_id,
                        room_type_id=EXCLUDED.room_type_id,name=EXCLUDED.name
                        WHERE room.department_id IN (SELECT id FROM hospital.department WHERE hospital_id=?)
                        RETURNING id
                        """, r.code(), reference(departments, r.departmentCode()),
                        reference(roomTypes, r.roomTypeCode()), r.name(), hospitalId);
                replaceEquipment(c, "room_equipment", "room_id", roomId, r.equipment(), equipment);
            }
            Map<String, Long> patients = new HashMap<>();
            for (Patient p : week.patients()) {
                long patientId = upsert(c, p.id(), """
                        INSERT INTO hospital.patient(code,display_name,email) VALUES (?,?,?)
                        ON CONFLICT (code) DO UPDATE SET display_name=EXCLUDED.display_name,email=EXCLUDED.email RETURNING id
                        """, p.code(), p.displayName(), p.email());
                patients.put(p.code(), patientId);
                List<Object[]> windows = new ArrayList<>();
                for (Window w : p.availability()) {
                    requireInWeek(week, w);
                    windows.add(new Object[]{patientId, w.startsAt(), w.endsAt()});
                }
                // Keep existing IDs, since requests may already reference these windows.
                batch(c, """
                        INSERT INTO hospital.patient_availability(patient_id,starts_at,ends_at) VALUES (?,?,?)
                        ON CONFLICT (patient_id,starts_at,ends_at) DO NOTHING
                        """, windows);
                execute(c, "DELETE FROM hospital.patient_time_preference WHERE patient_id=?", patientId);
                List<Object[]> preferences = new ArrayList<>();
                for (Preference pref : p.preferences()) {
                    preferences.add(new Object[]{patientId, pref.weekday().getValue(), pref.startsAt(), pref.endsAt(), pref.weight()});
                }
                batch(c, """
                        INSERT INTO hospital.patient_time_preference(patient_id,iso_weekday,local_start,local_end,weight)
                        VALUES (?,?,?,?,?)
                        """, preferences);
            }
            for (Request r : week.requests()) {
                Long patientId = patients.get(r.patientCode());
                if (patientId == null) {
                    List<Long> matches = query(c, "SELECT id FROM hospital.patient WHERE code=?",
                            row -> row.getLong("id"), r.patientCode());
                    if (matches.isEmpty()) throw new IllegalArgumentException("Unknown patient: " + r.patientCode());
                    patientId = matches.get(0);
                    patients.put(r.patientCode(), patientId);
                }
                Long roomTypeId = r.requiredRoomTypeCode() == null ? null : reference(roomTypes, r.requiredRoomTypeCode());
                long requestId = upsert(c, r.id(), """
                        INSERT INTO hospital.appointment_request
                        (code,patient_id,specialty_id,required_room_type_id,duration_minutes,urgency,requested_at,status)
                        VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (code) DO UPDATE SET patient_id=EXCLUDED.patient_id,
                        specialty_id=EXCLUDED.specialty_id,required_room_type_id=EXCLUDED.required_room_type_id,
                        duration_minutes=EXCLUDED.duration_minutes,urgency=EXCLUDED.urgency,
                        requested_at=EXCLUDED.requested_at,status=EXCLUDED.status
                        WHERE appointment_request.specialty_id IN (
                            SELECT s.id FROM hospital.specialty s JOIN hospital.department d ON d.id=s.department_id
                            WHERE d.hospital_id=?) RETURNING id
                        """, r.code(), patientId, reference(specialties, r.specialtyCode()), roomTypeId,
                        r.durationMinutes(), r.urgency().name(), r.requestedAt(), r.status().name(), hospitalId);
                execute(c, """
                        DELETE FROM hospital.request_availability a USING hospital.patient_availability w
                        WHERE a.patient_availability_id=w.id AND a.request_id=? AND w.starts_at<? AND w.ends_at>?
                        """, requestId, week.end(), week.start());
                List<Object[]> links = new ArrayList<>();
                for (Window w : r.availability()) {
                    requireInWeek(week, w);
                    List<Long> matches = query(c, """
                            SELECT id FROM hospital.patient_availability
                            WHERE patient_id=? AND starts_at=? AND ends_at=?
                            """, row -> row.getLong("id"), patientId, w.startsAt(), w.endsAt());
                    if (matches.isEmpty()) throw new IllegalArgumentException("Request window must belong to its patient");
                    links.add(new Object[]{requestId, patientId, matches.get(0)});
                }
                batch(c, """
                        INSERT INTO hospital.request_availability(request_id,patient_id,patient_availability_id) VALUES (?,?,?)
                        """, links);
                replaceEquipment(c, "request_equipment", "request_id", requestId, r.equipment(), equipment);
            }
            // Remove omitted windows last, after request links have been reconciled.
            for (Patient p : week.patients()) {
                long patientId = patients.get(p.code());
                Set<Window> retained = new HashSet<>(p.availability());
                List<WindowId> existing = query(c, """
                        SELECT id,starts_at,ends_at FROM hospital.patient_availability
                        WHERE patient_id=? AND starts_at<? AND ends_at>?
                        """, r -> new WindowId(r.getLong("id"), window(r)), patientId, week.end(), week.start());
                List<Object[]> removed = new ArrayList<>();
                for (WindowId w : existing) if (!retained.contains(w.window())) removed.add(new Object[]{w.id()});
                batch(c, "DELETE FROM hospital.patient_availability WHERE id=?", removed);
            }
            return null;
        });
    }

    private <T> T transaction(boolean readOnly, SqlWork<T> work) {
        try (Connection c = dataSource.getConnection()) {
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
            throw new HospitalRepositoryException("Could not " + (readOnly ? "load" : "save") + " hospital week", error);
        }
    }

    private static long upsert(Connection c, long expectedId, String sql, Object... parameters) throws SQLException {
        if (expectedId < 0) throw new IllegalArgumentException("ID cannot be negative");
        List<Long> result = query(c, sql, r -> r.getLong("id"), parameters);
        if (result.isEmpty()) throw new IllegalArgumentException("Entity code belongs to another hospital");
        long id = result.get(0);
        if (expectedId != 0 && expectedId != id) throw new IllegalArgumentException("Entity ID and code do not match");
        return id;
    }

    private static long reference(Map<String, Long> ids, String code) {
        Long id = ids.get(code);
        if (id == null) throw new IllegalArgumentException("Unknown or foreign entity code: " + code);
        return id;
    }

    private static Map<String, Long> ids(Connection c, String sql, Object... args) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        for (CodeId row : query(c, sql, r -> new CodeId(r.getString("code"), r.getLong("id")), args)) {
            result.put(row.code(), row.id());
        }
        return result;
    }

    private static <T> Map<Long, List<T>> doctorWindows(Connection c, String table, RowMapper<T> mapper,
                                                       Object... args) throws SQLException {
        return grouped(c, "SELECT w.*,w.doctor_id owner_id FROM hospital." + table + " w "
                + "JOIN hospital.doctor dr ON dr.id=w.doctor_id "
                + "JOIN hospital.department d ON d.id=dr.department_id "
                + "WHERE d.hospital_id=? AND w.starts_at<? AND w.ends_at>? "
                + "ORDER BY w.doctor_id,w.starts_at,w.ends_at", mapper, args);
    }

    private static <T> Map<Long, List<T>> grouped(Connection c, String sql, RowMapper<T> mapper,
                                                  Object... args) throws SQLException {
        Map<Long, List<T>> result = new HashMap<>();
        query(c, sql, r -> {
            result.computeIfAbsent(r.getLong("owner_id"), ignored -> new ArrayList<>()).add(mapper.map(r));
            return Boolean.TRUE;
        }, args);
        return result;
    }

    private static <T> List<T> values(Map<Long, List<T>> values, long id) {
        return values.getOrDefault(id, List.of());
    }

    private static void replaceDoctorWindows(Connection c, HospitalWeek week, long doctorId,
                                               String table, String labelColumn, List<DatedEntry> entries) throws SQLException {
        execute(c, "DELETE FROM hospital." + table + " WHERE doctor_id=? AND starts_at<? AND ends_at>?",
                doctorId, week.end(), week.start());
        List<Object[]> rows = new ArrayList<>();
        for (DatedEntry entry : entries) {
            requireInWeek(week, entry.window());
            rows.add(labelColumn == null
                    ? new Object[]{doctorId, entry.window().startsAt(), entry.window().endsAt()}
                    : new Object[]{doctorId, entry.window().startsAt(), entry.window().endsAt(), entry.label()});
        }
        // Table and column names are fixed internal constants, never user input.
        batch(c, "INSERT INTO hospital." + table + "(doctor_id,starts_at,ends_at"
                + (labelColumn == null ? ") VALUES (?,?,?)" : "," + labelColumn + ") VALUES (?,?,?,?)"), rows);
    }

    private static void replaceEquipment(Connection c, String table, String ownerColumn, long ownerId,
                                           List<EquipmentQuantity> equipment, Map<String, Long> ids) throws SQLException {
        execute(c, "DELETE FROM hospital." + table + " WHERE " + ownerColumn + "=?", ownerId);
        List<Object[]> rows = new ArrayList<>();
        for (EquipmentQuantity e : equipment) rows.add(new Object[]{ownerId, reference(ids, e.equipmentCode()), e.quantity()});
        batch(c, "INSERT INTO hospital." + table + "(" + ownerColumn + ",equipment_id,quantity) VALUES (?,?,?)", rows);
    }

    private static void requireInWeek(HospitalWeek week, Window window) {
        if (!window.startsAt().isBefore(week.end()) || !window.endsAt().isAfter(week.start())) {
            throw new IllegalArgumentException("Window must overlap the selected week");
        }
    }

    private static Window window(ResultSet r) throws SQLException {
        return new Window(instant(r, "starts_at"), instant(r, "ends_at"));
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        return r.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static EquipmentQuantity equipmentQuantity(ResultSet r) throws SQLException {
        return new EquipmentQuantity(r.getString("code"), r.getInt("quantity"));
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.length; i++) {
                Object value = parameters[i];
                statement.setObject(i + 1, value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
            }
            return statement;
        } catch (SQLException | RuntimeException error) {
            try { statement.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    private static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... parameters) throws SQLException {
        try (PreparedStatement s = prepare(c, sql, parameters); ResultSet r = s.executeQuery()) {
            List<T> result = new ArrayList<>();
            while (r.next()) result.add(mapper.map(r));
            return result;
        }
    }

    private static void execute(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = prepare(c, sql, parameters)) { s.executeUpdate(); }
    }

    private static void batch(Connection c, String sql, List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) return;
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    Object value = row[i];
                    s.setObject(i + 1, value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value);
                }
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    @FunctionalInterface private interface RowMapper<T> { T map(ResultSet row) throws SQLException; }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    private record CodeId(String code, long id) { }
    private record DatedEntry(Window window, String label) { }
    private record WindowId(long id, Window window) { }
}
