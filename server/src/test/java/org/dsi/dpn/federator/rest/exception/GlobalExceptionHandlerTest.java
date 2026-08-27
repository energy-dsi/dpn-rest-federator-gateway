// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.dsi.dpn.federator.rest.model.response.ErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() { handler = new GlobalExceptionHandler(); }

    @Test @DisplayName("handleValidation() returns 400 with VALIDATION_ERROR code")
    void handleValidation() throws Exception {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "mpan", "must be 13 digits"));
        var ex = new MethodArgumentNotValidException(null, bindingResult);
        ResponseEntity<ErrorResponse> resp = handler.handleValidation(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getCode()).isEqualTo("VALIDATION_ERROR");
    }

    @Test @DisplayName("handleIllegalArg() returns 400 with BAD_REQUEST code")
    void handleIllegalArg() {
        ResponseEntity<ErrorResponse> resp =
                handler.handleIllegalArg(new IllegalArgumentException("bad input"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getCode()).isEqualTo("BAD_REQUEST");
    }

    @Test @DisplayName("handleGeneral() returns 500 with INTERNAL_ERROR code")
    void handleGeneral() {
        ResponseEntity<ErrorResponse> resp =
                handler.handleGeneral(new RuntimeException("unexpected"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
    }
}
