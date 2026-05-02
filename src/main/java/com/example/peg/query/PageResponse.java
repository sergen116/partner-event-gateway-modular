package com.example.peg.query;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Paged result envelope")
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalItems / size) : 0;
        return new PageResponse<>(items, page, size, totalItems, totalPages);
    }
}
