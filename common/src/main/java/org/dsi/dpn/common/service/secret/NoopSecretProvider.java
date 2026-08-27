package org.dsi.dpn.common.service.secret;

public class NoopSecretProvider implements SecretProvider {
    @Override
    public String getSecret(String path, String key) {
        return null;
    }
}