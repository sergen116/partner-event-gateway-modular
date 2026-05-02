package com.example.peg.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void of_computesTotalPages_whenExactDivision() {
        var page = PageResponse.of(List.of("a", "b"), 0, 10, 100);
        assertThat(page.totalPages()).isEqualTo(10);
    }

    @Test
    void of_roundsUp_whenInexactDivision() {
        var page = PageResponse.of(List.of(), 0, 10, 95);
        assertThat(page.totalPages()).isEqualTo(10);
    }

    @Test
    void of_handlesEmpty() {
        var page = PageResponse.of(List.of(), 0, 50, 0);
        assertThat(page.totalPages()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void of_handlesSingleItemSinglePage() {
        var page = PageResponse.of(List.of("only"), 0, 50, 1);
        assertThat(page.totalPages()).isEqualTo(1);
    }
}
