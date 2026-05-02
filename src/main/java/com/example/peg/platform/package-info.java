/**
 * Platform-level concerns: runtime mode, scheduling, configuration,
 * observability. The seam between Spring infrastructure and feature modules.
 *
 * <p>Owns:
 * <ul>
 *   <li>{@link RuntimeProperties} — drives the seven-mode topology switch
 *       (API, CONSUMER_ALL, plus 5 per-type consumer modes)</li>
 *   <li>{@link WorkerRegistrationConfig} + {@link WorkerScheduler} — programmatic,
 *       mode-driven instantiation of {@code PgmqWorker} beans</li>
 *   <li>{@link ConsumerProperties}, {@link SecurityProperties} —
 *       {@code @ConfigurationProperties} bindings</li>
 *   <li>{@link SchedulingConfig} — multi-threaded TaskScheduler so polling
 *       loops don't serialize</li>
 *   <li>{@link JacksonConfig}, {@link OpenApiConfig} — framework wiring</li>
 *   <li>{@link QueueDepthExporter} — Micrometer gauges fed by pgmq.metrics()</li>
 * </ul>
 *
 * <p>Every feature module depends on {@code platform}; {@code platform}
 * depends only on {@code shared} and {@code delivery} (to register workers).
 */
package com.example.peg.platform;
