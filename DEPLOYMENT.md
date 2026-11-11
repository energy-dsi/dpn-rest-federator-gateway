# Deployment notes

## Manual secret: `dpn-backend-api-key`

The REST Federator gateway authenticates its outbound hop to the backend
(`demo-runner-server` in this repo's example deployment, or a real backend in
a production integration) with a shared API key read from the Kubernetes
Secret named by `restFederatorServer.backend.apiKeySecret.name` /
`demoRunnerServer.apiKeySecret.name` (default: `dpn-backend-api-key`, key
`apiKey`). This is REST-gateway-specific — the gRPC federator gateway has no
equivalent shared-secret hop — so it is not created by any Helm chart in this
repository and must be created once per namespace before the first deploy:

```bash
kubectl create secret generic dpn-backend-api-key \
  --namespace <namespace> \
  --from-literal=apiKey=<value>
```

Both `charts/dpn-rest-federator` and `charts/dpn-demo-runner-server` read the
same Secret name/key by default, so the gateway and backend agree on the
value without either side hard-coding it.

## Vault-sourced TLS material

Every chart's `vault.truststoreSecret.name` (default `cert-manager-truststore`)
and `vault.tokenSecret.name` / `vault.truststorePasswordSecret.name` /
`vault.appRole.secretIdSecret.name` (default `certificate-manager-secrets`)
follow the exact Secret names the `dpn-federator` gateway already uses in
each environment, so no separate secret-provisioning step is needed beyond
what that gateway's deployment already sets up in a shared namespace.

## CD pipeline: `VAULT-TRUSTSTORE-PASSWORD`

For environments where `restFederatorServer.vault.authMethod` is `token`
(e.g. `dev-dpn01`), `rest-federator-cd.yml` fetches `VAULT-TRUSTSTORE-PASSWORD`
from the environment's Azure Key Vault and passes it to Helm as
`--set restFederatorServer.vault.truststorePassword=...`, mirroring
`dpn-federator`'s `azure-dpn-cd.yaml`. No manual step is required here as long
as that Key Vault secret already exists (it is shared with the federator
gateway's own deployment in the same environment).
