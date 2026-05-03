package com.example.peg.shared;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @Test
    void exposesStatusCodeAndMessage() {
        ApiException ex = new ApiException(HttpStatus.CONFLICT, "DUP", "duplicate");
        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.code()).isEqualTo("DUP");
        assertThat(ex.getMessage()).isEqualTo("duplicate");
    }

    @Test
    void isRuntimeException() {
        ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, "X", "y");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
