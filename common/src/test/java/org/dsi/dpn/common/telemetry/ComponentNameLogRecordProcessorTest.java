// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ComponentNameLogRecordProcessor} - previously entirely untested.
 * Package-private, so this test lives in the same package to construct it directly.
 */
class ComponentNameLogRecordProcessorTest {

    @Test
    @DisplayName("onEmit() stamps the configured component name onto the log record under the "
            + "'component.name' attribute key")
    void onEmit_stampsComponentNameAttribute() {
        ComponentNameLogRecordProcessor processor = new ComponentNameLogRecordProcessor("dpn-rest-federator");
        ReadWriteLogRecord logRecord = mock(ReadWriteLogRecord.class);

        processor.onEmit(Context.root(), logRecord);

        verify(logRecord).setAttribute(AttributeKey.stringKey("component.name"), "dpn-rest-federator");
        verifyNoMoreInteractions(logRecord);
    }

    @Test
    @DisplayName("Two processors constructed with different component names stamp their own "
            + "distinct value onto each record they process")
    void onEmit_differentComponentNames_stampDistinctValues() {
        ComponentNameLogRecordProcessor serverProcessor = new ComponentNameLogRecordProcessor("rest-federator-server");
        ComponentNameLogRecordProcessor clientProcessor = new ComponentNameLogRecordProcessor("demo-runner-client");
        ReadWriteLogRecord serverRecord = mock(ReadWriteLogRecord.class);
        ReadWriteLogRecord clientRecord = mock(ReadWriteLogRecord.class);

        serverProcessor.onEmit(Context.root(), serverRecord);
        clientProcessor.onEmit(Context.root(), clientRecord);

        verify(serverRecord).setAttribute(AttributeKey.stringKey("component.name"), "rest-federator-server");
        verify(clientRecord).setAttribute(AttributeKey.stringKey("component.name"), "demo-runner-client");
    }
}
