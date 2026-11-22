SET LOCAL search_path = hospital, public;

CREATE TABLE benchmark_instance (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    data_version text NOT NULL,
    name text NOT NULL,
    schedule_week_id bigint REFERENCES schedule_week(id),
    request_count integer NOT NULL CHECK (request_count > 0),
    definition jsonb NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    known_optimum numeric CHECK (known_optimum >= 0 AND known_optimum < 'Infinity'::numeric),
    optimum_evidence text,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key),
    CHECK ((known_optimum IS NULL) = (optimum_evidence IS NULL)),
    UNIQUE (code, data_version)
);

CREATE TABLE experiment (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    instance_id bigint NOT NULL REFERENCES benchmark_instance(id),
    name text NOT NULL,
    objective text NOT NULL,
    direction text NOT NULL DEFAULT 'MINIMIZE' CHECK (direction = 'MINIMIZE'),
    synthetic boolean NOT NULL,
    description text NOT NULL,
    demo_dataset_key text REFERENCES demo_dataset(dataset_key)
);

CREATE TABLE experiment_run (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code text NOT NULL UNIQUE,
    experiment_id bigint NOT NULL REFERENCES experiment(id),
    algorithm_id bigint NOT NULL REFERENCES algorithm(id),
    random_seed bigint NOT NULL,
    parameters jsonb NOT NULL CHECK (jsonb_typeof(parameters) = 'object'),
    budget_ms bigint NOT NULL CHECK (budget_ms > 0),
    elapsed_ms bigint CHECK (elapsed_ms >= 0),
    status text NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    final_fitness numeric CHECK (final_fitness >= 0 AND final_fitness < 'Infinity'::numeric),
    hard_violations integer CHECK (hard_violations >= 0),
    synthetic boolean NOT NULL,
    started_at timestamptz NOT NULL CHECK (isfinite(started_at)),
    demo_dataset_key text REFERENCES demo_dataset(dataset_key),
    CONSTRAINT experiment_run_completed_result CHECK (status <> 'COMPLETED' OR
        (final_fitness IS NOT NULL AND hard_violations IS NOT NULL AND elapsed_ms IS NOT NULL)),
    UNIQUE (experiment_id, algorithm_id, random_seed)
);

CREATE TABLE fitness_point (
    run_id bigint NOT NULL REFERENCES experiment_run(id),
    step integer NOT NULL CHECK (step >= 0),
    elapsed_ms bigint NOT NULL CHECK (elapsed_ms >= 0),
    fitness numeric NOT NULL CHECK (fitness >= 0 AND fitness < 'Infinity'::numeric),
    PRIMARY KEY (run_id, step)
);

CREATE INDEX benchmark_instance_week_idx ON benchmark_instance(schedule_week_id);
CREATE INDEX experiment_instance_idx ON experiment(instance_id);
CREATE INDEX experiment_run_algorithm_idx ON experiment_run(algorithm_id);
CREATE INDEX experiment_run_results_idx ON experiment_run(experiment_id, algorithm_id, final_fitness)
    WHERE status = 'COMPLETED';

COMMENT ON TABLE fitness_point IS
    'Ordered best-so-far objective values. Completion, monotone elapsed time, and equality of the last point with the run result are application responsibilities, checked for the demo by verification SQL.';
