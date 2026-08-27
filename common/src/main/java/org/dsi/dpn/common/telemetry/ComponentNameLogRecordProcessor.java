// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;

/**
 * Stamps a "component.name" attribute onto every log record emitted by this JVM, derived from
 * OTEL_SERVICE_NAME, so log records are attributable to a component without needing
 * {@code .addKeyValue("component.name", ...)} at every call site.
 *
 * <p>Ported from dpn-federator's telemetry package (same class, same behaviour).
 */
final class ComponentNameLogRecordProcessor implements LogRecordProcessor {

    private static final AttributeKey<String> COMPONENT_NAME = AttributeKey.stringKey("component.name");

    private final String componentName;

    ComponentNameLogRecordProcessor(String componentName) {
        this.componentName = componentName;
    }

    @Override
    public void onEmit(Context context, ReadWriteLogRecord logRecord) {
        logRecord.setAttribute(COMPONENT_NAME, componentName);
    }
}
