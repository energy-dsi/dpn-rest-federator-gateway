// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026.
package org.dsi.dpn.common.utils;

/**
 * Properties-file bootstrap keys.
 *
 * <p>These previously lived on the gRPC Federator's {@code FederatorServer}
 * entry point and were reached through a static wildcard import. They are
 * collected here so this library does not depend on gRPC server/client classes
 * that the REST stack has no use for.
 */
public final class ConfigKeys {

    private ConfigKeys() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated");
    }

    /** Environment variable naming the properties file to load. */
    public static final String ENV_SERVER_PROPS = "FEDERATOR_SERVER_PROPERTIES";

    /** Default properties file name when the environment variable is unset. */
    public static final String SERVER_PROPERTIES = "server.properties";

    /** Key whose value is the path of the shared common-configuration file. */
    public static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
}
