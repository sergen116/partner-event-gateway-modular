package com.example.peg.shared;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorsTest {

    @Test
    void unauthorized_mapsTo401() {
        ApiException ex = Errors.unauthorized("nope");
        assertThat(ex.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.code()).isEqualTo("UNAUTHORIZED");
        assertThat(ex.getMessage()).isEqualTo("nope");
    }

    @Test
    void forbidden_mapsTo403() {
        ApiException ex = Errors.forbidden("nope");
        assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void badRequest_carriesCallerCode() {
        ApiException ex = Errors.badRequest("INVALID_PAGE", "page must be >= 0");
        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("INVALID_PAGE");
        assertThat(ex.getMessage()).isEqualTo("page must be >= 0");
    }

    @Test
    void notFound_mapsTo404() {
        ApiException ex = Errors.notFound("missing");
        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void conflict_carriesCallerCode() {
        ApiException ex = Errors.conflict("DUP", "duplicate");
        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.code()).isEqualTo("DUP");
    }
}
