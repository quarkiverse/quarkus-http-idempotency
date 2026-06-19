# Contributing to Quarkus HTTP Idempotency

Thank you for considering contributing to this project!

## Ways to Contribute

- Report bugs
- Suggest new features
- Improve documentation
- Submit code changes via Pull Requests

## Development Setup

Requirements:

- Java 17+ (JDK 21 is used by CI, including the native build)
- Maven 3.9+
- Quarkus 3.x

Build and run the full test suite locally:

```bash
mvn clean install
```

Build the native integration tests (uses a container build, no local GraalVM needed):

```bash
cd integration-tests
mvn verify -Dnative -Dquarkus.native.container-build=true
```

## Pull Requests

- Open PRs against `main`. Keep each PR focused on a single concern.
- The build runs the formatter and import sorter; run `mvn clean install` before pushing
  so your changes are already formatted (CI fails on unformatted code).
- Add or update tests for any behavior change. The deployment module hosts the
  `QuarkusUnitTest`-based tests; `integration-tests` hosts the JVM and native ITs.
- Update the documentation under `docs/modules/ROOT/pages/` when you change configuration
  or behavior.

## Reporting Security Issues

Please do not open public issues for security vulnerabilities. Report them privately
to the maintainers so a fix can be prepared before disclosure.
