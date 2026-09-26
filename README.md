# README

**Repository:** `dpn-rest-federator-gateway`

**Description:** `Provides a REST-based mechanism to exchange data between Data Preparation Nodes (DPN)`

<!-- SPDX-License-Identifier: Apache-2.0 AND OGL-UK-3.0 -->

---

## Overview

This repository contributes to the development of **secure, scalable, and interoperable data-sharing infrastructure**. It supports DSI's mission to enable **trusted, federated, and decentralised** data-sharing across organisations.

This repository is one of several open-source components that underpin DSI's **Data Preparation Node (DPN)**—a framework designed to allow organisations to manage and exchange data securely while maintaining control over their own information. The DPN is actively deployed and tested across multiple sectors, ensuring its adaptability and alignment with real-world needs.

The REST Federator Gateway is a **REST/HTTPS-based alternative to the gRPC Federator**, aimed at producers and consumers who need to exchange data over a synchronous, request/response API rather than a streamed Kafka-to-Kafka pipeline. It reuses the same Management Node, Identity Provider, and certificate-management infrastructure as the gRPC Federator, so both federation styles can be run side by side against a single DPN control plane.

## Prerequisites

* Java 21
* [Docker](https://www.docker.com/)
* [Git](https://git-scm.com/)

## Configuration & Installation

Detailed configuration and installation instructions for this repository are present in **[dpn-integration-playbook](https://github.com/energy-dsi/dpn-integration-playbook)**

This includes producer/consumer setup, CI/CD pipeline configuration and execution, and deployment validation. Refer to the guide matching your deployment target:

### AWS Deployment

Refer [aws-manual-beta](https://github.com/energy-dsi/dpn-integration-playbook/tree/main/Docs/03-dpn-application-deployment/aws-manual-beta) for AWS specific deployment 

**Note** AWS Manual deployment is an interim solution and GitHub Actions based deployment to replace the manual deployment in future release

### Azure Deployment

Refer [azure-ado-beta](https://github.com/energy-dsi/dpn-integration-playbook/tree/main/Docs/03-dpn-application-deployment/azure-ado-beta) for Azure specific deployment

## Features

The REST Federator enables secure, synchronous data exchange between Data Preparation Nodes, supporting both server (producer) and client (consumer) roles over plain HTTPS. Key features include:

### Data Federation
- Secure, request/response data sharing over REST — no Kafka broker required on either side.
- Multiple REST Federator servers and clients per organisation for flexible deployment.
- Communication between the REST Federator server and client uses HTTPS with mutual TLS (mTLS) for secure, authenticated data transfer.
- Producers expose product-specific REST endpoints; consumers are authorised per-product, per-method and per-path via the Management Node's product configuration.

### Security
- **Mutual TLS**: server and client both present and validate certificates using Spring Boot SSL bundles, with hot-reload on certificate rotation (no restart required).
- **JWT authentication**: inbound Bearer tokens are validated against the DSM Identity Provider's JWKS endpoint (Keycloak), with client identity resolved from the token's `azp`/`client_id` claim.
- **Method and path authorisation**: a dedicated authorisation filter verifies the calling consumer is registered against a REST-type product, and that the request's HTTP method and path are permitted by that product's allowed-path configuration.
- **OCSP revocation checking**: both server and client independently verify the counterparty's certificate has not been revoked via the Management Node's OCSP endpoint before completing a request.
- **Backend API key**: the gateway authenticates itself to the internal backend with a shared secret, since the backend sits outside the DPN trust chain.
- **Security response headers**: standard OWASP-recommended headers (HSTS, `X-Content-Type-Options`, `X-Frame-Options`, `Cache-Control: no-store`) are applied to every response.
- **Sanitised error handling**: a global exception handler returns structured, sanitised error responses (no stack traces or internal details).

### Common Infrastructure
- Integration with Management-Node for centralised configuration, product management, and authorisation — the same control plane used by the gRPC Federator.
- Signed JWT-based authentication with the DSM Identity Provider for consumer verification and authorisation.
- Certificate keystores/truststores are shared with the federator-certificate-manager, so certificate rotation is picked up automatically without redeploying.
- **OpenTelemetry observability**: traces, spans and structured logs are exported via OTLP, fail-open (a missing or unreachable collector never blocks startup), with `trace_id`/`span_id` correlation in the logs — the same telemetry approach as the gRPC Federator.

### Connectivity and security
- Multiple REST Federator producers and consumers can exchange data across organisations using HTTPS over mTLS.
- As long as they are configured to talk to the same Management-Node, they will obtain compatible configuration (products, allowed paths, endpoints) required for their data exchange.
- The Management-Node, together with the Identity Provider, issues the certificates/credentials and tokens that enable mutual TLS and authorisation, and serves the OCSP status used to detect revoked certificates.
- This means any number of producers and consumers can safely exchange data so long as their exchange requirements are defined in, and served by, the Management-Node.

### Exchange data between DPN nodes

The REST Federator is designed to allow synchronous, API-driven data exchange between Data Preparation Nodes. It supports the standard REST verbs — GET, POST, PUT, PATCH and DELETE — for lookups, record registration and updates.

#### Server (Producer) - Simplified View

The gateway is a **generic authorising reverse proxy** — it carries no business logic and no knowledge of any particular data product:

1. Terminates mTLS and validates the caller's JWT against the DSM Identity Provider.
2. Verifies the caller is a registered consumer of a REST data product via the Management Node's configuration.
3. Verifies the caller's certificate has not been revoked, via an OCSP check against the Management Node.
4. Checks the request's **HTTP method and path** against the product's allowed-path configuration.
5. Forwards the authorised request — method, path, query string, headers and body — verbatim to the configured internal backend, and returns the backend's response verbatim.

The gateway-to-backend hop is authenticated with a shared API key, since the backend does not participate in the DPN trust chain.

#### Client (Consumer) - Simplified View

1. Bootstraps from the Management Node at startup (`getConsumerConfig`) to discover the products this consumer is subscribed to, each with its producing organisation, producer id, base URL, and allowed paths.
2. Builds a per-product mTLS-enabled HTTP client, watching its certificate files for rotation and rebuilding the client transparently when they change.
3. A call selects its target product by **`ProductKey(organisation, productName)`** — the two labels a participant knows; the internal producer id and base URL are resolved from consumer config. The client checks the producer's certificate has not been revoked via OCSP, but does **not** police the request path — path authorisation is the gateway's job (see below).
4. Attaches a Bearer token (obtained from the DSM Identity Provider) and invokes the producer's REST endpoint.

The underlying communication protocol is plain HTTPS (REST) over mTLS, providing secure, authenticated data transfer between servers and clients — using the same certificate and identity infrastructure as the gRPC Federator, without requiring gRPC or Kafka.

## Integrating the `rest-federator-client` library

A participant writes their own consumer program and drives the calls; the library handles all the plumbing (Management Node bootstrap, mTLS, JWT, OCSP, certificate hot-reload). `demo-runner-client` is a worked example of exactly these steps — any consumer, calling any REST endpoints, follows the same shape.

**1. Add the dependency**

```xml
<dependency>
  <groupId>org.dsi.dpn</groupId>
  <artifactId>rest-federator-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

**2. Provide configuration.** A `client.properties` naming a `common-configuration.properties` (the same files the gRPC Federator client uses); point the `FEDERATOR_CLIENT_PROPERTIES` environment variable at it (or place it on the classpath). The common configuration supplies what the client needs:

| Key(s) | Purpose |
|---|---|
| `management.node.base.url` (+ `management.node.request.timeout`, `management.node.cache.ttl.seconds`) | Where the client bootstraps its subscriptions from. |
| `idp.auth.mode` (e.g. `private_key_jwt`), `idp.client.id`, `idp.jwks.url`, `idp.token.url`, `idp.jwt.key.alias` | Identity Provider / JWT acquisition. |
| `idp.keystore.path`/`password`, `idp.truststore.path`/`password` | mTLS material — or the `vault.*` keys to source it from Vault instead. |

**3. Initialise, then construct the client.** `PropertyUtil` must be initialised **before** `new RestClient()`. Constructing the client bootstraps it: it calls the Management Node, and builds one registration per product you're subscribed to (e.g. one from each of 5 organisations → 5 entries), then starts a certificate watcher.

```java
OpenTelemetryConfig.initialize();               // optional — traces/logs
PropertyUtil.init(new File(System.getenv()
        .getOrDefault("FEDERATOR_CLIENT_PROPERTIES", "client.properties")));

RestClient client = new RestClient();           // bootstraps from getConsumerConfig
```

**4. Discover your subscriptions** (each keyed by `ProductKey(organisation, productName)`):

```java
client.getAllRegistrations().forEach((key, reg) ->
        log.info("{} -> {}  allowedPaths={}", key, reg.baseUrl(), reg.allowedPaths()));
```

**5. Make calls.** Address a product by the two labels you know — organisation and product name. The internal producer id, base URL and mTLS client are resolved from consumer config; you never supply the producer id. All five HTTP verbs are available, each with an optional custom-headers map (`Authorization` is owned by the client):

```java
ProductKey orgB = new ProductKey("Org B", "Data Product X");
Map<String,String> headers = Map.of("X-My-Header", "value");

String body    = client.get(orgB, "/api/v1/things?id=42", headers);
String created = client.post(orgB, "/api/v1/things", "{\"name\":\"...\"}", headers);
String updated = client.put(orgB, "/api/v1/things/42", "{...}");
                 client.patch(orgB, "/api/v1/things/42", "{...}");
                 client.delete(orgB, "/api/v1/things/42");
```

**Calling several organisations/products (including multiple products from the same organisation).** `RestClient` holds *every* subscription, so there's nothing extra to pass — loop over `getAllRegistrations()` (or a filtered subset) and reuse the one client. A consumer subscribed to one product from each of five organisations gets five entries; an organisation offering several products contributes one entry per product:

```java
// Call every product this consumer is subscribed to:
client.getAllRegistrations().keySet()
      .forEach(key -> client.get(key, "/api/v1/things", headers));

// Or just one organisation's products:
client.getAllRegistrations().keySet().stream()
      .filter(key -> key.organisation().equals("Org A"))
      .forEach(key -> client.get(key, "/api/v1/things", headers));
```

The `demo-runner-client` does exactly this — its `ORGANISATION_NAME` / `PRODUCT_NAME` inputs filter the registry to the targets to run (see [Reference Implementation & Demo](#reference-implementation--demo)).

**6. (Optional) fail fast on disallowed paths.** The gateway enforces the product's allowed paths authoritatively, so this is not required — but a participant can pre-check locally from the same registry to avoid a round-trip:

```java
client.getRegistration(orgB).ifPresent(reg -> {
    if (!reg.isAllowed("GET", "/api/v1/things/42")) {
        throw new IllegalStateException("not in this product's allowed paths");
    }
});
```

**Errors to expect:** `IllegalArgumentException` (you named a product you're not subscribed to), `IllegalStateException` (the producer's certificate failed the OCSP check), and `RuntimeException` wrapping a `WebClientResponseException` for HTTP errors from the gateway (e.g. `403` for a disallowed path, `409` conflict) — the innermost cause carries the status.

## Repository Layout

| Path | Artifact | Purpose |
|---|---|---|
| [`common/`](common/) | `rest-federator-common` | Plumbing shared by the gateway and the client: configuration loading, mTLS, IDP token acquisition, Management Node communication. Vendored — see [`common/VENDORED.md`](common/VENDORED.md). |
| [`server/`](server/) | `rest-federator-server` | **The gateway.** Security chain plus the generic backend proxy. Deployed as a container. |
| [`client/`](client/) | `rest-federator-client` | Importable **library jar** consumers add as a dependency — `RestClient` (mTLS, JWT, OCSP, Management Node bootstrap, certificate hot-reload). Exposes `get`/`post`/`put`/`patch`/`delete(ProductKey, path[, body][, headers])`; callers pass custom headers (e.g. `X-*`) via the `Map<String,String>` argument — `Authorization` is owned by the client. |
| [`demo/demo-runner-server/`](demo/demo-runner-server/) | `demo-runner-server` | **Example only.** Standalone backend implementing FMAR asset endpoints, deployed as its own container. |
| [`demo/demo-runner-client/`](demo/demo-runner-client/) | `demo-runner-client` | **Example only.** Consumer application importing `rest-federator-client`; runs as a one-shot Kubernetes Job. |

`server` and `client` are the two sides of a federated exchange: the gateway
receives requests, the client library makes them. The gateway is the sole
authority on authorisation (consumer + method/path, from producer config); the
client library handles the plumbing (consumer-config bootstrap, mTLS, JWT, OCSP)
and lets the integrator drive the calls.

The root pom is an aggregator, so one command builds everything in dependency
order:

```bash
mvn clean install
```

### Allowed-path configuration

A REST product's allowed paths come from DSM in the product's `topic` field, as a JSON array of method/path pairs. Path patterns support URI template variables (`{fspId}`, matching one segment) and Ant wildcards (`*`, `**`); matching is on the request method and path only — the query string is ignored:

```json
[{"request_type":"GET","request_path":"/api/v1/fmar/assets"},
 {"request_type":"POST","request_path":"/api/v1/fmar/fsp/{fspId}/assets"}]
```

The **gateway** (`DsiProductAuthorizationFilter`) enforces this authoritatively on every request, from producer config — regardless of the client. The **client** (`RestClient`) deliberately does **not** police paths; it only does the plumbing (consumer-config bootstrap, mTLS, JWT, OCSP) and sends the call. An integrator that wants a client-side pre-check can read `getRegistration(key).allowedPaths()` (parsed from the same `topic`) and validate against it themselves. A product with no usable configuration denies everything server-side.

### Gateway configuration

| Property | Environment variable | Purpose |
|---|---|---|
| `backend.base-url` | `BACKEND_BASE_URL` | Host and port of the internal backend to forward to. |
| `backend.api-key` | `BACKEND_API_KEY` | Shared secret sent to the backend as `X-Backend-Api-Key`. Sourced from a Kubernetes Secret — never committed. |

## Reference Implementation & Demo

The [`demo/`](demo/) folder holds a runnable, end-to-end example built **on top of** the REST Federator rather than being part of it. It is illustrative only — data is held in memory and is not persisted.

### Example backend — `demo-runner-server`

A standalone Spring Boot service, deployed in its own container, implementing a subset of the [MHHS FMAR specification](https://api.swaggerhub.com/apis/MHHSPROGRAMME/fmar-specification-api-0.5draft/0.5.0-draft). Paths carry an `/api/v1/fmar` prefix for consistency with this gateway's other routes — the spec itself defines the bare `/assets` and `/fsp/{fspId}/assets`:

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/fmar/assets` | Query an asset by import MPAN. Query params `importMpan`, `postcode`, optional `assetId`; headers `X-Sender-FMAR-Id`, `X-Sender-Role`, `X-Contractual-Authorisation`. |
| `POST /api/v1/fmar/fsp/{fspId}/assets` | Register a new asset (`FMAR001` request body). Returns `409` if the import MPAN is already registered. |

Both return the `FMAR002_AssetRegistrationRequestResponse` schema. Swagger UI is exposed at `/swagger-ui.html`. It terminates HTTPS using the same Vault-issued certificate the gateway uses (server-only TLS — no client certificate required on the gateway-to-backend hop). Callers must present the shared API key; requests without it are rejected with `401`.

### Example consumer — `demo-runner-client`

A plain Java application (no Spring) that imports `rest-federator-client` and calls the producer **through the gateway**, exercising:

1. `GET /api/v1/fmar/assets?importMpan=...&postcode=...` — before registration (expects not found)
2. `POST /api/v1/fmar/fsp/{fspId}/assets` — register the asset
3. `GET /api/v1/fmar/assets?importMpan=...&postcode=...` — now found
4. `POST /api/v1/fmar/fsp/{fspId}/assets` — same MPAN again (expects a conflict)
5. `GET /api/v1/fmar/assets/getClientID` — a path deliberately **not** in the product's allowed-path configuration. The client does not police paths, so the call reaches the gateway, which rejects it with `403`, demonstrating server-side method/path enforcement.

Results are printed as formatted, pretty-printed log blocks so a run is readable in `kubectl logs`.

**Multiple organisations / products in one run.** The runner resolves its targets by **filtering the client's subscription registry** (everything from `getConsumerConfig`) against `ORGANISATION_NAME` and `PRODUCT_NAME` — each of which may be a single value, a comma-separated list, or blank:
- **both blank** — every product this consumer is subscribed to;
- **organisation(s) only** — every product those organisations offer, so a single org with several products yields one target per product;
- **product(s) only** — every organisation offering those products;
- **both** — the intersection (named organisations offering named products).

Because targets are drawn from the registry, only genuinely subscribed products are selected (a typo can't produce a "not subscribed" call), and the demo runs the FMAR sequence against each — each target under its own trace span. This mirrors how a real integrator would loop: `RestClient` already holds every subscription, so calling several is just building a `ProductKey(organisation, productName)` per target.

Worked examples — given a consumer subscribed to **`(Org A, P1)`, `(Org A, P2)`, `(Org B, P1)`, `(Org C, P3)`**:

| `ORGANISATION_NAME` | `PRODUCT_NAME` | Targets run |
|---|---|---|
| *(blank)* | *(blank)* | `(Org A,P1)`, `(Org A,P2)`, `(Org B,P1)`, `(Org C,P3)` — everything |
| `Org A` | *(blank)* | `(Org A,P1)`, `(Org A,P2)` — one org, all its products |
| *(blank)* | `P1` | `(Org A,P1)`, `(Org B,P1)` — one product, all orgs offering it |
| `Org A` | `P1` | `(Org A,P1)` — a single target |
| `Org A,Org B` | `P1` | `(Org A,P1)`, `(Org B,P1)` |
| `Org A` | `P1,P2` | `(Org A,P1)`, `(Org A,P2)` |
| `Org A,Org C` | `P2,P3` | `(Org A,P2)`, `(Org C,P3)` — the intersection |
| `Org Z` | `P1` | *(none — "No target products resolved" warning; not subscribed)* |

It runs as a one-shot Kubernetes Job. A Job's pod spec is immutable once created, so parameters cannot be passed to an existing Job — instead **each run creates a new Job object**, with `job.nameSuffix` set to the build id and the run parameters injected as container environment variables:

```bash
helm upgrade --install dpn-demo-runner-client charts/dpn-demo-runner-client \
  -n <namespace> \
  --set job.nameSuffix=$(date +%s) \
  --set job.env.organisationName="DPN01-PRODUCER" \
  --set job.env.productName="FMAR Asset Registration" \
  --set job.env.mpan=1000000000001 \
  --set job.env.postcode="SW1A 1AA"

kubectl logs job/dpn-demo-runner-client-<suffix> -n <namespace>
```

The `demo-runner-cd.yml` pipeline exposes `organisationName`, `productName`, `mpan`, `postcode`, `assetId` and `fspId` as pipeline parameters and feeds them through those same values.

## Pipelines

| Pipeline | Covers |
|---|---|
| `.pipelines/azure-pipelines/ci-pipelines/rest-federator-ci.yml` | Gateway **and** client library: Checkmarx + FOSSA, tests, Sonar, both images. |
| `.pipelines/azure-pipelines/ci-pipelines/demo-runner-ci.yml` | Both demo applications: Checkmarx + FOSSA, tests, Sonar, both images. |
| `.pipelines/azure-pipelines/cd-pipelines/rest-federator-cd.yml` | Redis cache and the gateway, as separate stages. |
| `.pipelines/azure-pipelines/cd-pipelines/demo-runner-cd.yml` | Demo backend deployment, and the demo client Job. |

Both CD pipelines gate **every** deploy stage with an approval integrated into the stage itself — an environment-bound approval check (`dsi-dev` / `dsi-devtest` / `dsi-ppd`, routed by environment) that the real deploy job depends on. Because the approval lives inside each deploy stage rather than in a separate stage, it cannot be bypassed by deselecting stages in the "Stages to run" selector at queue time.

Helm charts: [`charts/dpn-rest-federator`](charts/dpn-rest-federator/) (gateway), [`charts/dpn-redis`](charts/dpn-redis/) (cache — its own chart and release, so it deploys and rolls back independently), [`charts/dpn-demo-runner-server`](charts/dpn-demo-runner-server/), and [`charts/dpn-demo-runner-client`](charts/dpn-demo-runner-client/) (Job).

## Public Funding Acknowledgment

This repository has been developed with public funding as part of the Data Sharing Infrastructure (DSI), a UK Government initiative. DSI, alongside its partners, has invested in this work to advance open, secure, and reusable digital twin technologies for any organisation, whether from the public or private sector, irrespective of size.

## License

This repository contains both source code and documentation, which are covered by different licenses:
- **Code:** Licensed under the [Apache License 2.0](./LICENSE.md).
- **Documentation:** Licensed under the [Open Government Licence (OGL) v3.0](./OGL_LICENSE.md).

By contributing to this repository, you agree that your contributions will be licenced under these terms.

See [`LICENSE.md`](./LICENSE.md), [`OGL_LICENSE.md`](./OGL_LICENSE.md) and [`NOTICE.md`](./NOTICE.md) for details.

## Security and Responsible Disclosure

We take security seriously. If you believe you have found a security vulnerability in this repository, please follow our responsible disclosure process outlined in [SECURITY.md](./SECURITY.md).

## Contributing

We welcome contributions that align with the Programme's objectives. Please read our [CONTRIBUTING.md](./CONTRIBUTING.md) guidelines before submitting pull requests.

## Acknowledgements  
This repository has benefited from collaboration with various organisations. For a list of acknowledgments, see [ACKNOWLEDGEMENTS.md](./ACKNOWLEDGEMENTS.md).  

## Support and Contact

For questions, feedback, or support requests:

- Contact DSI team via email to [dsi@neso.energy](mailto:dsi@neso.energy)

## Maintained by the National Energy System Operator (NESO)

Copyright 2026 NESO.  This work is licensed under the Open Government Licence 3.0 (OGL). This work has been developed by NESO using content licensed by the Department for Business and Trade (UK) under the OGL.   
 
Licensed under the Open Government Licence v3.0.

For full licensing terms, [OGL_LICENSE.md](./OGL_LICENSE.md)
