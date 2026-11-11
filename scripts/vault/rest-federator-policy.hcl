# Vault ACL policy for the rest-federator's AppRole identity.
#
# rest-federator-server reads its TLS keystore/truststore passwords from Vault
# under vault.tlsBasePath ("node-net/client") -- the same base path
# certificate-manager writes PKI material into (see
# dpn-federator-cert-manager/scripts/vault/certificate-manager-policy.hcl),
# inferred from the matching path string since this chart's own Java source
# isn't in this repo to confirm the exact KV mount name against. Read-only:
# unlike certificate-manager, rest-federator never issues/rotates certs here.
#
# sys/mounts read is required by the same Spring Cloud Vault
# ensureKvMountExists() check certificate-manager's policy documents.

path "sys/mounts" {
  capabilities = ["read"]
}

path "pki-client/data/node-net/client/*" {
  capabilities = ["read", "list"]
}

path "pki-client/metadata/node-net/client/*" {
  capabilities = ["read", "list"]
}
