# Changelog

**Repository:** `dpn-rest-federator-gateway`  
**Description:** `Tracks all notable changes, version history, and roadmap toward 1.0.0 following Semantic Versioning.`

<!-- SPDX-License-Identifier: OGL-UK-3.0 -->

--- 

All notable changes to this repository will be documented in this file.

This project follows **Semantic Versioning (SemVer)** ([semver.org](https://semver.org/)), using the format:

`[MAJOR].[MINOR].[PATCH]`
- **MAJOR** (`X.0.0`) – Incompatible API/feature changes that break backward compatibility.
- **MINOR** (`0.X.0`) – Backward-compatible new features, enhancements, or functionality changes.
- **PATCH** (`0.0.X`) – Backward-compatible bug fixes, security updates, or minor corrections.
- **Pre-release versions** – Use suffixes such as `-alpha`, `-beta`, `-rc.1` (e.g., `2.1.0-beta.1`).
- **Build metadata** – If needed, use `+build` (e.g., `2.1.0+20260314`).

---

## How to Update This Changelog

1. When making changes, update this file under the **Unreleased** section.
2. Before a new release, move changes from **Unreleased** to a new dated section with a version number.
3. Follow **Semantic Versioning** rules to categorise changes correctly.
4. If pre-release versions are used, clearly mark them as `-alpha`, `-beta`, or `-rc.X`.

---
# Changelog

**Repository:** `dpn-rest-federator-gateway`  
**Description:** `Tracks all notable changes, version history, and roadmap toward 1.0.0 following Semantic Versioning.`

<!-- SPDX-License-Identifier: OGL-UK-3.0 -->

--- 

All notable changes to this repository will be documented in this file.

This project follows **Semantic Versioning (SemVer)** ([semver.org](https://semver.org/)), using the format:

`[MAJOR].[MINOR].[PATCH]`
- **MAJOR** (`X.0.0`) – Incompatible API/feature changes that break backward compatibility.
- **MINOR** (`0.X.0`) – Backward-compatible new features, enhancements, or functionality changes.
- **PATCH** (`0.0.X`) – Backward-compatible bug fixes, security updates, or minor corrections.
- **Pre-release versions** – Use suffixes such as `-alpha`, `-beta`, `-rc.1` (e.g., `2.1.0-beta.1`).
- **Build metadata** – If needed, use `+build` (e.g., `2.1.0+20260314`).

---

## How to Update This Changelog

1. When making changes, update this file under the **Unreleased** section.
2. Before a new release, move changes from **Unreleased** to a new dated section with a version number.
3. Follow **Semantic Versioning** rules to categorise changes correctly.
4. If pre-release versions are used, clearly mark them as `-alpha`, `-beta`, or `-rc.X`.

---

## Release 1.1.1 - November 2026

### Added
- Shared DPN: 
- - Updates to the REST API gateway for DPN-to-DPN federation, required for shared DPN on Azure
- - Added per-instance server deployment, load balancer service, service account and secret provider class, with dev and test values for shared DPN instances 02 and 03

### Fixed
- AWS: Config file hot fix for the REST Federator Gateway AWS deployment - updated the AWS CD workflows and value files for the REST federator and the demo runner server and client, and added a service account and secret provider class for the REST federator server
- Checkmarx SCA findings: bumped jackson, tomcat, micrometer, logback and otel, pinned reactor-netty and excluded android-json

### Removed
- None

## Release 1.1.0 - Oct 2026

### Added
- OAuth2-authenticated OTLP export (`OtelBearerTokenSupport`) — reuses the existing
  `dpn-service-client` Redis-gating client and its Redis token cache; no new secret.
- Matching server-side enforcement on the OTel Collector (TLS + `oidc` auth extension),
  with OAuth2/TLS support extended to `demo-runner-server` and `demo-runner-client`, all opt-in.
- end-to-end for `demo-runner-client`, including HTTPS transport and
  Bearer token authentication via Keycloak.

### Fixed
- `OpenTelemetryConfig` now applies `OtelBearerTokenSupport` alongside the existing TLS-trust
  support, independently, per OTLP exporter.
- New `oauth2.enabled`/`tls.enabled`/`auth.enabled` flags across four charts
- `OtelBearerTokenSupport`'s token-service build moved from eager to lazy, fixing a
  `PropertyUtil not initialised` failure on every startup with this feature enabled.

### Removed
- None

## Release 1.0.0 - Sep 2026

- Initial version of restful Federator Gateway
- Functionality to connect DSI DSM usign Mutual TLS and Certificate
- Functionality to verify certificate using OCSP checks
- Functionality to validate allowed paths of restful APIs
- Functionality to showcase restful API service using a demo runner client and server
- Functionality to validate token audience and technology sharing agreements received dynamically from DSI DSM

---

## Maintained by the National Energy System Operator (NESO)

Copyright 2026 NESO.  This work is licensed under the Open Government Licence 3.0 (OGL). This work has been developed by NESO using content licensed by the Department for Business and Trade (UK) under the OGL.   
 
Licensed under the Open Government Licence v3.0.

For full licensing terms, [OGL_LICENSE.md](./OGL_LICENSE.md)

