# Hospital Scheduler: Core API Contracts

**Technical specification**  
**Module:** `scheduler-core`  
**Language:** Java 17  
**Packages:** `com.hospitalscheduler.model`, `com.hospitalscheduler.optimization`  
**Scope:** Initial shared core API

## 1. Purpose and scope

This document specifies the common data types and interfaces used by the Hospital Scheduler's scheduling and optimization components. It sets a stable integration boundary between the hospital domain model, fitness evaluation, optimization algorithms, persistence, and presentation layers.

All algorithms work on the same problem representation, produce the same schedule representation, and expose results through the same optimization contract. This makes integration consistent and algorithm comparisons meaningful, without coupling the core to JavaFX or PostgreSQL.

This document describes the **initial implemented API**. Future extensions are listed separately and must not be assumed to be available.

## 2. Architectural boundaries

| Component | Responsibility | Dependency rule |
| --- | --- | --- |
| Domain model | Represent hospital resources, requests, and scheduled appointments | Independent of database and UI |
| Optimization contracts | Define inputs, outputs, fitness, and progress callbacks | Depend on core domain types |
| Algorithm implementations | Construct or improve schedules | Implement the shared `Optimizer` interface |
| Fitness implementation | Assess constraint violations and soft costs | Implement the shared `FitnessEvaluator` interface |
| Persistence adapter | Load and save hospital data and schedules | Translate stored data into core types |
| JavaFX application | Configure runs and display progress and results | Consume core contracts; keep UI concerns outside core |

The core module defines contracts. It does not prescribe a specific optimization algorithm, database schema, or user-interface implementation.

## 3. Domain model

The package `com.hospitalscheduler.model` defines the following types.

| Type | Role |
| --- | --- |
| `Patient` | Patient identity |
| `Doctor` | Doctor identity, specialty, working hours, and unavailable periods; exposes `isAvailable(TimeSlot)` |
| `Room` | Consultation room identity and available equipment |
| `TimeSlot` | Time interval with a start and end |
| `AppointmentRequest` | Requested consultation: patient, specialty, duration, urgency, availability, and equipment needs |
| `Appointment` | Assignment of a request to a doctor, room, and time slot |
| `Schedule` | Collection of scheduled appointments |

`Doctor.isAvailable(TimeSlot)` returns true when the slot lies entirely inside one of the doctor's working-hour intervals and does not overlap any unavailable interval. It throws `IllegalArgumentException` for a null slot. It covers only the doctor's own availability, not specialty, room, or patient constraints.

### 3.1 Appointment and schedule construction

```java
Appointment appointment = new Appointment(request, doctor, room, timeSlot);
Schedule schedule = new Schedule(List.of(appointment));
Schedule emptySchedule = new Schedule(List.of());
```

`Schedule` requires a `List<Appointment>` argument; it has **no zero-argument constructor**. The current implementation rejects duplicate appointment-request IDs within a schedule by throwing `IllegalArgumentException`. A schedule may be incomplete or violate hospital constraints; constructing a `Schedule` does not establish feasibility.

### 3.2 Time interval semantics

`TimeSlot` uses `LocalDateTime` endpoints and half-open interval semantics, **`[start, end)`**. Two intervals that meet at a common endpoint do not overlap. The constructor requires non-null endpoints with `start` strictly before `end`, and throws `IllegalArgumentException` otherwise. `overlaps` checks intersection, while `contains` checks interval containment.

### 3.3 Domain invariants

Validation in the domain model is partial. In the current implementation:

- `Appointment` requires all four constructor arguments to be non-null.
- `TimeSlot` enforces non-null endpoints and `start < end`.
- `Schedule` rejects duplicate request IDs.
- `Patient`, `Room`, `Doctor`, and `AppointmentRequest` do not validate their fields. For example, a null patient or specialty, a zero or negative duration, or any urgency value is accepted. Passing a null list or set to these constructors fails with a `NullPointerException` from the defensive copy, not from an explicit check.
  Full scheduling feasibility therefore requires additional checks, which belong to the fitness evaluator. In particular, an appointment may need verification against doctor specialty and availability, patient availability, room equipment, and simultaneous resource assignments. These concerns must not be conflated with schedule completeness.

## 4. Optimization problem

The optimization package provides a common problem container:

```java
OptimizationProblem problem = new OptimizationProblem(
    doctors,
    rooms,
    requests
);
 
List<Doctor> doctors = problem.getDoctors();
List<Room> rooms = problem.getRooms();
List<AppointmentRequest> requests = problem.getRequests();
```

`OptimizationProblem` makes defensive copies of the supplied lists. A null list or a null element throws a `NullPointerException`. Algorithms should treat the input problem as read-only and must not mutate shared problem data as a side effect of optimization.

## 5. Fitness evaluation contract

### 5.1 Evaluator interface

```java
public interface FitnessEvaluator {
    FitnessBreakdown evaluate(
        OptimizationProblem problem,
        Schedule schedule
    );
}
```

A production fitness implementation evaluates the same scheduling constraints for every algorithm. Algorithm implementations should use the shared evaluator rather than introducing algorithm-specific scoring rules for comparable experiments.

### 5.2 Fitness representation and ordering

```java
FitnessBreakdown score = new FitnessBreakdown(hardViolations, softCost);
 
int violations = score.hardViolations();
double cost = score.softCost();
boolean improved = score.isBetterThan(previousScore);
```

`FitnessBreakdown` contains:

- `hardViolations`: nonnegative number of hard-constraint violations.
- `softCost`: finite, nonnegative soft-constraint cost.
  Invalid values throw `IllegalArgumentException`. `isBetterThan` also throws `IllegalArgumentException` when given null.

**Comparison is lexicographic and minimized:**

1. A candidate with fewer hard violations is always preferred.
2. If hard violations are equal, the candidate with the lower soft cost is preferred.
   Consequently, a reduction in soft cost never compensates for an increase in hard violations. Implementations must use this common ordering when reporting or selecting their best candidate.

### 5.3 Completeness is a separate concern

```java
boolean complete = ScheduleCompletenessValidator.isComplete(problem, schedule);
```

`ScheduleCompletenessValidator` is a utility class with a static `isComplete` method. It returns true when the schedule contains every request in the problem exactly once and nothing else. Requests are matched by ID only, and the method does not null-check its arguments. If the problem itself contains duplicate request IDs, the method can never return true, because a `Schedule` cannot hold duplicates.

This structural property does **not** imply that doctor, patient, room, specialty, or equipment constraints are satisfied. Fitness evaluation and completeness validation serve different purposes.

## 6. Optimization execution contract

### 6.1 Common algorithm interface

```java
public interface Optimizer {
    String getName();
 
    OptimizationResult optimize(
        OptimizationRequest request,
        ProgressListener listener
    );
}
```

Each optimization algorithm implements `Optimizer`, accepts the common request, reports progress through the listener, and returns an `OptimizationResult`. The listener is expected to be non-null; the interface does not define behavior for a null listener, and the test `FakeOptimizer` rejects one with a `NullPointerException`. Callers that do not care about progress should pass a no-op listener.

### 6.2 Input types

```java
public record OptimizationParameters(long seed, int maxIterations) { }
 
public record OptimizationRequest(
    OptimizationProblem problem,
    OptimizationParameters parameters
) { }
```

The seed supports reproducible stochastic runs when an algorithm uses randomness. `maxIterations` must be nonnegative. Required request fields must be non-null.

### 6.3 Progress notification

```java
public record OptimizationProgress(
    int iteration,
    FitnessBreakdown bestFitness
) { }
 
@FunctionalInterface
public interface ProgressListener {
    void onProgress(OptimizationProgress progress);
}
```

The iteration number must be nonnegative and the reported fitness must be non-null. Progress is communicated through a callback, so the presentation layer can observe an algorithm without introducing JavaFX dependencies into the core. Any required UI-thread dispatching is the responsibility of the UI integration layer.

### 6.4 Result type

```java
public record OptimizationResult(
    Schedule bestSchedule,
    FitnessBreakdown bestFitness,
    List<FitnessBreakdown> history,
    String status
) { }
```

The result carries the best schedule, its fitness, a history of fitness values, and an execution status. Required fields must be non-null, and the history list is defensively copied. The current status representation is a **`String`**, not an enum; no status vocabulary is standardized by this initial contract.

### 6.5 Integration example

```java
OptimizationRequest request = new OptimizationRequest(
    problem,
    new OptimizationParameters(42L, 1000)
);
 
OptimizationResult result = optimizer.optimize(
    request,
    progress -> System.out.println(
        "Iteration " + progress.iteration()
            + ": " + progress.bestFitness()
    )
);
 
Schedule bestSchedule = result.bestSchedule();
FitnessBreakdown bestFitness = result.bestFitness();
```

Here `optimizer` denotes any concrete implementation of `Optimizer`. The example does not imply that all production algorithms are already implemented.

## 7. Cross-component integration requirements

1. **Single model:** All components exchange the shared domain types rather than maintaining incompatible copies of schedules or requests.
2. **Single algorithm API:** Every optimization implementation conforms to `Optimizer`.
3. **Consistent evaluation:** Comparable algorithm runs use the same fitness implementation, constraint definitions, and comparison rule.
4. **Dependency direction:** The core remains independent of JavaFX and PostgreSQL; adapters translate data at integration boundaries.
5. **Stable public signatures:** Changes to shared constructors, interfaces, and records require review and matching updates to tests and this document.
6. **Reproducibility:** Stochastic algorithms should honor the supplied seed where applicable; experimental comparisons must record the relevant parameters.
7. **Correct claims:** A heuristic result must not be described as proven globally optimal, or as proof of infeasibility, without an appropriate exact-method justification.
## 8. Verification

The core includes JUnit tests for domain behavior, schedule completeness, fitness-value rules, and optimizer-contract integration. A test-only `FakeOptimizer` exercises the interface and progress callback. Its synthetic empty schedule and zero fitness are **not** a production solution to a nonempty scheduling problem.

From the repository root on Windows:

```powershell
.\mvnw.cmd -pl scheduler-core -am clean test
```

A successful build verifies the compiled core and its current tests. It does not establish correctness of unimplemented algorithms or end-to-end hospital scheduling.

PostgreSQL migrations, database setup and the four-PC verification checklist are
documented in the [persistence module](../scheduler-persistence/README.md).
