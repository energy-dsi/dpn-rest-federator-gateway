package org.dsi.dpn.common.service.secret;

public interface SecretProvider {
    String getSecret(String path, String key);
    default boolean isEnabled() {
        return false;
    }
}
