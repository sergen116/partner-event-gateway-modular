package com.example.peg.platform;

import com.example.peg.shared.EventType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Lets {@code @RequestParam EventType} bind from the wire name (e.g. "OrderCreated")
 * rather than the Java enum name. Without this, Spring falls back to {@code valueOf}
 * and rejects valid wire names with a 500. The Jackson {@code @JsonCreator} on
 * {@link EventType} only covers JSON bodies, not query params.
 */
@Component
public class EventTypeConverter implements Converter<String, EventType> {
    @Override
    public EventType convert(@NonNull String source) {
        return EventType.fromWireName(source);
    }
}
