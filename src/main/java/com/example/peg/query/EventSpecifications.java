package com.example.peg.query;

import com.example.peg.shared.EventStatus;
import com.example.peg.shared.EventType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Composable filter specifications for event queries.
 *
 * <p>This is the "extensible filter" requirement from the case spec made
 * concrete. Adding a new filter is two steps:
 * <ol>
 *   <li>Add a field to {@link EventQuery}</li>
 *   <li>Add an entry to {@link #SPECS} below</li>
 * </ol>
 *
 * <p>The shape mirrors Spring Data's {@code Specification<T>} API — each
 * specification is a function that takes the query and emits an optional
 * {@code (sql, args)} pair. Composing them is just iteration. We don't pull
 * in JPA here because the rest of the data layer uses {@link
 * org.springframework.jdbc.core.JdbcTemplate} (see ARCHITECTURE.md ADR-006);
 * a thin specification API gives us the same extensibility property without
 * the ORM weight.
 *
 * <p>Note: time-bound filters {@code from} / {@code to} use the partition
 * key. When set, they prune partitions at plan time. Queries without bounds
 * scan all active partitions — still bounded by the table's retention
 * window, but slower than necessary.
 */
public final class EventSpecifications {

    private EventSpecifications() {}

    /**
     * One specification = one optional WHERE clause + its bound parameters.
     */
    @FunctionalInterface
    public interface Spec {
        /**
         * @param q     the query
         * @param args  collect bound parameters into this list (in order)
         * @return SQL clause body without leading "AND", or {@code null} if the
         *         filter is not active for this query
         */
        String render(EventQuery q, List<Object> args);
    }

    /**
     * The full registry of supported filters. Adding a new one is one
     * line here plus the corresponding field on {@link EventQuery}.
     */
    private static final List<Spec> SPECS = List.of(
            partnerId(),
            eventType(),
            status(),
            processingOutcome(),
            fromCreatedAt(),
            toCreatedAt(),
            businessRef()
    );

    public static SqlFragment compile(EventQuery q) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        boolean any = false;
        for (Spec spec : SPECS) {
            String clause = spec.render(q, args);
            if (clause != null) {
                where.append(any ? " AND " : " WHERE ").append(clause);
                any = true;
            }
        }
        return new SqlFragment(where.toString(), args);
    }

    public record SqlFragment(String whereClause, List<Object> args) {}

    private static Spec partnerId() {
        return optional(EventQuery::getPartnerId, "partner_id = ?");
    }

    private static Spec eventType() {
        return (q, args) -> {
            EventType t = q.getEventType();
            if (t == null) return null;
            args.add(t.name());
            return "event_type = ?";
        };
    }

    private static Spec status() {
        return (q, args) -> {
            EventStatus s = q.getStatus();
            if (s == null) return null;
            args.add(s.name());
            return "status = ?";
        };
    }

    private static Spec processingOutcome() {
        return (q, args) -> {
            EventStatus o = q.getProcessingOutcome();
            if (o == null) return null;
            args.add(o.name());
            return "status = ?";
        };
    }

    private static Spec fromCreatedAt() {
        return (q, args) -> {
            Instant t = q.getFromCreatedAt();
            if (t == null) return null;
            args.add(Timestamp.from(t));
            return "created_at >= ?";
        };
    }

    private static Spec toCreatedAt() {
        return (q, args) -> {
            Instant t = q.getToCreatedAt();
            if (t == null) return null;
            args.add(Timestamp.from(t));
            return "created_at <= ?";
        };
    }

    private static Spec businessRef() {
        return optional(EventQuery::getBusinessRef, "business_ref = ?");
    }

    private static <T> Spec optional(Function<EventQuery, T> getter, String clause) {
        return (q, args) -> {
            T v = getter.apply(q);
            if (v == null) return null;
            args.add(v);
            return clause;
        };
    }
}
