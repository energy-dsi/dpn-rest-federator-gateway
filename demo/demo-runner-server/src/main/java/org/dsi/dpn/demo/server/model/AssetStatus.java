// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * assetStatus values, per the FMAR specification.
 *
 * <p>Registration (FMAR001) accepts only {@link #AWAITING_ENERGISATION} and
 * {@link #ENERGISED}; the wider set can appear on a query response (FMAR002).
 * Wire values are the spec's display strings (e.g. "Awaiting Energisation").
 */
public enum AssetStatus {
    SPECULATIVE("Speculative"),
    PLANNED("Planned"),
    AWAITING_ENERGISATION("Awaiting Energisation"),
    ENERGISED("Energised"),
    WITHDRAWN("Withdrawn"),
    REMOVED("Removed");

    private final String wireValue;

    AssetStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String getWireValue() {
        return wireValue;
    }

    @JsonCreator
    public static AssetStatus fromWireValue(String value) {
        if (value == null) return null;
        for (AssetStatus status : values()) {
            if (status.wireValue.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown assetStatus: " + value);
    }

    /** Whether this status is permitted on a new registration request. */
    public boolean isValidForRegistration() {
        return this == AWAITING_ENERGISATION || this == ENERGISED;
    }
}
