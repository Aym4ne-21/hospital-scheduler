\set ON_ERROR_STOP on
SET search_path = hospital, public;
-- These are SYNTHETIC illustrations, not benchmark conclusions.
SELECT i.code AS instance,i.data_version,a.name AS algorithm,a.version,
       count(*) AS runs,min(r.final_fitness) AS best,
       round(avg(r.final_fitness),3) AS mean,
       round(stddev_samp(r.final_fitness),3) AS sample_stddev,
       sum(r.hard_violations) AS total_violations,
       round(avg(r.hard_violations),3) AS mean_violations,
       round(avg(r.elapsed_ms),1) AS mean_elapsed_ms,
       i.known_optimum,
       min(r.final_fitness)-i.known_optimum AS absolute_gap,
       (min(r.final_fitness)-i.known_optimum)/nullif(abs(i.known_optimum),0) AS relative_gap
FROM experiment_run r JOIN experiment e ON e.id=r.experiment_id
JOIN benchmark_instance i ON i.id=e.instance_id JOIN algorithm a ON a.id=r.algorithm_id
WHERE e.synthetic AND r.synthetic AND r.status='COMPLETED'
GROUP BY i.id,a.id ORDER BY i.code,a.code;

SELECT DISTINCT ON (e.instance_id,r.algorithm_id)
       i.code AS instance,a.name AS algorithm,r.code AS best_run,r.random_seed,
       r.final_fitness,r.hard_violations,r.budget_ms,r.elapsed_ms,r.synthetic
FROM experiment_run r JOIN experiment e ON e.id=r.experiment_id
JOIN benchmark_instance i ON i.id=e.instance_id JOIN algorithm a ON a.id=r.algorithm_id
WHERE e.synthetic AND r.synthetic AND r.status='COMPLETED'
ORDER BY e.instance_id,r.algorithm_id,r.final_fitness,r.elapsed_ms,r.code;

SELECT r.code AS run,r.synthetic,p.step,p.elapsed_ms,p.fitness
FROM experiment_run r JOIN fitness_point p ON p.run_id=r.id
WHERE r.code=coalesce(nullif(current_setting('project15.run_code',true),''),'demo15.run.week.genetic.01')
ORDER BY p.step;
