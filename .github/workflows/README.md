# GitHub Actions CD

These workflows are self-contained and runnable directly from this repo — `workflow_dispatch` from the Actions tab, no dependency on any other repo.

- `dpn-gha-rest-federator-cd.yaml` — installs the REST Federator gateway and its own Redis.
- `dpn-gha-demo-runner-cd.yaml` — installs the demo runner server + client.
- `dpn-gha-rest-federator-rollback.yaml` — `helm rollback` one of the 4 releases (`dpn-redis`, `dpn-rest-federator`, `dpn-demo-runner-server`, `dpn-demo-runner-client`) to a specific or auto-resolved previous revision. Requires typing the release name again to confirm.
- `dpn-gha-rest-federator-uninstall.yaml` — `helm uninstall` for **all 4 releases in one run** (type `uninstall-all` to confirm), matching the real ADO `rest-federator-uninstall-cd.yml` pipeline's scope exactly — it has no per-release selector either.

**Moved out, not duplicated here:** `dpn-gha-certificate-manager-role-id-creation.yaml` used to carry a copy of this component's AppRole setup too (via a shared `component` input), overlapping with the dedicated workflow above. Relocated to `dpn-federator-gateway` instead — that repo's Federator Gateway had no dedicated AppRole workflow at all (it was sharing Certificate Manager's role_id directly as a documented stopgap), so it's a better home for the generic template than a second copy of rest-federator's own already-covered setup.

**Known gap, not something to fix here:** this repo's fifth chart, `charts/dpn-otel-collector`, has no CD workflow anywhere in the source repo and no per-cloud values either.

## One-time setup required (not a workflow change)

- **GitHub Environments** named `<environment>-<cluster>` for each target (e.g. `dev-dpn01`, `test-dpn02`).
- **Repository or Environment secrets**: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` (Azure OIDC federated identity).
- **AWS path**: relies on OIDC role-chaining into the shared hub role, then your account's `oidc_github_runner_admin` role — no AWS secret needed, but that role/trust must exist.
- **Self-hosted runner**: targets `runs-on: [self-hosted, vmss]` — register a runner with that label, or change `runs-on` to a pool you control.
- **Per-environment config**: `.github/config/{aws,azure}/<environment>-<cluster>.json` already has real target values — update if pointing at different infrastructure.

Nothing in the workflow logic itself needs editing to run these.
