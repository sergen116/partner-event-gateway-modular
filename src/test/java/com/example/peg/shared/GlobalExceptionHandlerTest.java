package com.example.peg.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getRequestURI()).thenReturn("/api/v1/events");
    }

    @Test
    void handleApi_unauthorized_logsInfo_andReturnsEnvelope() {
        ApiException ex = Errors.unauthorized("nope");
        ResponseEntity<ErrorResponse> resp = handler.handleApi(ex, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("UNAUTHORIZED");
        assertThat(resp.getBody().message()).isEqualTo("nope");
        assertThat(resp.getBody().timestamp()).isNotNull();
    }

    @Test
    void handleApi_forbidden_logsInfo() {
        ApiException ex = Errors.forbidden("denied");
        ResponseEntity<ErrorResponse> resp = handler.handleApi(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void handleApi_otherStatus_logsWarn() {
        ApiException ex = Errors.badRequest("BAD", "bad");
        ResponseEntity<ErrorResponse> resp = handler.handleApi(ex, request);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("BAD");
    }

    @Test
    void handleValidation_concatenatesFieldErrors() throws Exception {
        Method m = Sample.class.getDeclaredMethod("doIt", String.class);
        MethodParameter param = new MethodParameter(m, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Sample(), "sample");
        bindingResult.addError(new org.springframework.validation.FieldError(
                "sample", "name", null, false, new String[]{"NotNull"}, null, "must not be null"));
        bindingResult.addError(new org.springframework.validation.FieldError(
                "sample", "age", null, false, new String[]{"Min"}, null, "must be >= 0"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(param, bindingResult);
        ResponseEntity<ErrorResponse> resp = handler.handleValidation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(resp.getBody().message())
                .contains("name must not be null")
                .contains("age must be >= 0");
    }

    @Test
    void handleIllegalArg_returns400() {
        ResponseEntity<ErrorResponse> resp =
                handler.handleIllegalArg(new IllegalArgumentException("oops"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("BAD_REQUEST");
        assertThat(resp.getBody().message()).isEqualTo("oops");
    }

    @Test
    void handleUnreadable_unwrapsIllegalArgumentCause_andReturns400() {
        // Mirrors what Jackson does when @JsonCreator throws: wrap IllegalArgumentException
        // in a JsonMappingException-like cause, then Spring wraps that in HttpMessageNotReadableException.
        IllegalArgumentException root = new IllegalArgumentException("unknown event type: NotARealType");
        RuntimeException jacksonWrapper = new RuntimeException("instantiation failed", root);
        HttpInputMessage inputMessage = Mockito.mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("JSON parse error", jacksonWrapper, inputMessage);

        ResponseEntity<ErrorResponse> resp = handler.handleUnreadable(ex, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo("INVALID_REQUEST_BODY");
        assertThat(resp.getBody().message()).isEqualTo("unknown event type: NotARealType");
    }

    @Test
    void handleUnreadable_fallsBackToGenericMessage_whenNoIllegalArgInChain() {
        HttpInputMessage inputMessage = Mockito.mock(HttpInputMessage.class);
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("JSON parse error",
                        new RuntimeException("trailing comma at line 3"), inputMessage);

        ResponseEntity<ErrorResponse> resp = handler.handleUnreadable(ex, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("INVALID_REQUEST_BODY");
        assertThat(resp.getBody().message()).isEqualTo("malformed request body");
    }

    @Test
    void handleGeneric_logsAndReturnsOpaqueMessage() {
        Mockito.when(request.getRequestURI()).thenReturn("/x");
        ResponseEntity<ErrorResponse> resp =
                handler.handleGeneric(new RuntimeException("internal failure detail"), request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().code()).isEqualTo("INTERNAL_ERROR");
        // body should not leak internal detail
        assertThat(resp.getBody().message()).isEqualTo("internal server error");
    }

    static class Sample {
        public String name;
        public int age;
        public void doIt(String x) {}
    }
}
