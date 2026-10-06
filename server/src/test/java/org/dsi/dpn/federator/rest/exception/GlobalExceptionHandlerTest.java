// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    /**
     * AC1/AC2 — the logged event must carry the human-readable message AND the full stack
     * trace, including a nested root cause, together in the same log entry (not just the
     * top-level exception message, and not as separate log lines).
     */
    @Test @DisplayName("handleGeneral() logs the message and full stack trace, incl. nested "
            + "root cause, together in one log entry")
    void handleGeneral_logsMessageAndStackTraceTogether() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Exception rootCause = new IllegalStateException("root cause");
            Exception ex = new RuntimeException("outer failure", rootCause);

            handler.handleGeneral(ex);

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);

            // AC2: human-readable message logged alongside the stack trace, same entry.
            assertThat(event.getFormattedMessage()).isEqualTo("Unhandled exception");

            // AC1: full stack trace captured, including the nested/root cause chain.
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(RuntimeException.class.getName());
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("outer failure");
            assertThat(event.getThrowableProxy().getCause()).isNotNull();
            assertThat(event.getThrowableProxy().getCause().getClassName())
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getThrowableProxy().getCause().getMessage())
                    .isEqualTo("root cause");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
