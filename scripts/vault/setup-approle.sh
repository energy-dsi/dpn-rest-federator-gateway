#!/usr/bin/env bash
#
# Configures HashiCorp Vault AppRole authentication for the rest-federator component
# against the DPN vault. Mirrors
# dpn-federator-cert-manager/scripts/vault/setup-approle.sh exactly, adapted for
# rest-federator's own role name/policy.
#
# Prerequisites:
#   - VAULT_ADDR and VAULT_TOKEN (a token with sufficient privileges to manage auth
#     methods, policies and AppRole roles) are exported in the environment.
#   - The KV v2 secrets engine referenced by rest-federator-policy.hcl already exists,
#     e.g.  vault secrets enable -path=pki-client -version=2 kv
#
# Usage:
#   ./setup-approle.sh
#
set -euo pipefail

ROLE_NAME="rest-federator"
POLICY_NAME="rest-federator-policy"
POLICY_FILE="$(dirname "$0")/rest-federator-policy.hcl"

echo "Enabling AppRole auth method (no-op if already enabled)..."
vault auth enable approle 2>/dev/null || echo "  approle auth method already enabled"

echo "Writing policy '${POLICY_NAME}'..."
vault policy write "${POLICY_NAME}" "${POLICY_FILE}"

echo "Creating/updating AppRole role '${ROLE_NAME}'..."
vault write "auth/approle/role/${ROLE_NAME}" \
    token_policies="${POLICY_NAME}" \
    token_ttl=1h \
    token_max_ttl=4h \
    secret_id_ttl=0 \
    secret_id_num_uses=0

echo
echo "role_id (set as VAULT_APPROLE_ROLE_ID, not sensitive):"
vault read -field=role_id "auth/approle/role/${ROLE_NAME}/role-id"

echo
echo "secret_id (set as VAULT_APPROLE_SECRET_ID, TREAT AS SENSITIVE):"
vault write -field=secret_id -f "auth/approle/role/${ROLE_NAME}/secret-id"
