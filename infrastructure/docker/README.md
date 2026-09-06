# Runtime image contracts

These Dockerfiles do not inspect or prescribe application build tools.

- Backend build context must contain `app.jar`, an executable Spring Boot artifact.
- Frontend build context must contain Next.js standalone output as `standalone/`, static assets as `static/`, and `public/`. Configure `output: "standalone"` in the application only if compatible.

CI builds application artifacts first, then builds these runtime images, emits BuildKit SBOM/provenance attestations, scans the published digest, signs it using keyless GitHub OIDC, and deploys immutable digests. Runtime base images are pinned by digest.
