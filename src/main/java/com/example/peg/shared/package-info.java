/**
 * Shared types used across all modules: domain enums ({@link EventType},
 * {@link EventStatus}), record-shaped DTOs ({@link EventRecord},
 * {@link PartnerEventMessage}), the framework's error envelope, and the
 * global exception translator.
 *
 * <p>Modules consume from {@code shared}; {@code shared} consumes from no
 * other application module. This dependency direction is the modular
 * monolith's load-bearing rule — feature modules never depend on each other,
 * only on {@code shared} and on Spring/JDK platform.
 */
package com.example.peg.shared;
