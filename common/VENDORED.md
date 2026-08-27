# Vendored code — provenance

The classes under `src/main/java/org/dsi/dpn/common/` are vendored from the
gRPC Federator so this repository does not depend on an externally published
artifact (`uk.gov.dbt.ndtp:federator`) or on credentials for the repository
that produces it.

| | |
|---|---|
| Source repository | `dpn-federator-gateway` |
| Source branch | `develop` |
| Source commit | `ee88f1c94a976db23d9186e0c0b811656d349a48` |
| Vendored on | 2026-08-25 |
| Original package | `uk.gov.dbt.ndtp.federator.*` |
| Vendored package | `org.dsi.dpn.common.*` |

## What was taken

Configuration loading and secret resolution (`PropertyUtil`,
`service/secret/*` including Vault AppRole, token renewal and
TLS-material-from-Vault), mTLS (`SSLUtils`, `HttpClientFactoryUtils`),
Keycloak token acquisition for all three client-authentication modes
(`service/idp/*`), Management Node communication with its retry and circuit
breaker (`management/*`, `service/config/*`, `ResilienceSupport`), the Redis
token cache (`RedisUtil`, `AesCryptoUtil`) and the payload DTOs
(`model/dto/*`).

## What was changed

* Packages renamed, as above.
* `GRPCUtils` renamed `IdpTokenServiceFactory` and reduced to
  `createIdpTokenService()`. Its gRPC channel-credential and file-transfer
  checksum helpers are unused by the REST stack; removing them drops the
  `io.grpc` dependency entirely.
* Constants previously reached through static wildcard imports of the gRPC
  entry-point classes (`FederatorServer`, `GRPCClient`) are collected in
  `utils/ConfigKeys`.

Nothing else was edited. Keeping the rest byte-identical is deliberate: it
makes the next re-sync a diff rather than a merge.

## Re-syncing

```bash
git -C ../dpn-federator fetch origin develop
git -C ../dpn-federator diff ee88f1c94a976db23d9186e0c0b811656d349a48 origin/develop --   src/main/java/uk/gov/dbt/ndtp/federator/common/   src/main/java/uk/gov/dbt/ndtp/federator/exceptions/
```

Apply anything relevant, re-run the package rename, re-apply the two changes
above, then rebuild all five modules.

**Note:** upstream fixes — including security fixes to the token or mTLS
paths — no longer arrive automatically. Re-check this diff when the federator
team announces a security release, and update the commit above when you do.
