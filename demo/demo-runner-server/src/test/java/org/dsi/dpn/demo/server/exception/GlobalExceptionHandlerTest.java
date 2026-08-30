// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.dsi.dpn.demo.server.store.InMemoryAssetStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test @DisplayName("duplicate MPAN -> 409 with conflicting MPANs")
    void duplicate() {
        var ex = new InMemoryAssetStore.DuplicateMpanException(List.of("1000000000001"));
        ResponseEntity<ErrorResponse> res = handler.handleDuplicate(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().getCode()).isEqualTo("DUPLICATE_MPAN");
        assertThat(res.getBody().getConflictingMpans()).containsExactly("1000000000001");
    }

    @Test @DisplayName("body validation -> 400 BAD_REQUEST")
    void bodyValidation() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(new FieldError("req", "mpan", "must not be blank")));
        ResponseEntity<ErrorResponse> res = handler.handleValidation(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getCode()).isEqualTo("BAD_REQUEST");
    }

    @Test @DisplayName("parameter validation -> 400 BAD_REQUEST")
    void paramValidation() {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        when(ex.getMessage()).thenReturn("invalid");
        assertThat(handler.handleParamValidation(ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test @DisplayName("missing query parameter -> 400 naming the parameter")
    void missingParam() {
        var ex = new MissingServletRequestParameterException("importMpan", "String");
        ResponseEntity<ErrorResponse> res = handler.handleMissingParam(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).contains("importMpan");
    }

    @Test @DisplayName("missing header -> 400 naming the header")
    void missingHeader() {
        MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
        when(ex.getHeaderName()).thenReturn("X-Backend-Api-Key");
        ResponseEntity<ErrorResponse> res = handler.handleMissingHeader(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).contains("X-Backend-Api-Key");
    }

    @Test @DisplayName("IllegalArgumentException -> 400 passing the message through")
    void illegalArg() {
        ResponseEntity<ErrorResponse> res = handler.handleIllegalArg(new IllegalArgumentException("bad input"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).isEqualTo("bad input");
    }

    @Test @DisplayName("unhandled exception -> 500 INTERNAL_ERROR, no internal detail leaked")
    void general() {
        ResponseEntity<ErrorResponse> res = handler.handleGeneral(new RuntimeException("boom stacktrace"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(res.getBody().getMessage()).isEqualTo("An unexpected error occurred.");
    }
}
