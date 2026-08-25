// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.model.response;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.*;
/** Standard error envelope — mirrors ErrorResponse schema in Swagger spec */
@Data @AllArgsConstructor @NoArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private String code;
    private String message;
    private List<String> conflictingMpans;
    private List<FieldError> details;

    @Data @AllArgsConstructor @NoArgsConstructor @Builder
    public static class FieldError {
        private String field;
        private String issue;
    }
}
