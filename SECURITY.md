# Security Policy

## Project status

TraceLite is a small-scale portfolio project under active development. It has not been audited and is not intended for production use without further hardening. Known limitations are tracked in [docs/security.md](docs/security.md).

## Supported versions

Only the latest commit on `main` receives fixes.

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub's private vulnerability reporting ("Report a vulnerability" under the repository's **Security** tab) and include:

- A description of the issue and its impact
- Steps to reproduce
- Affected commit or version

## Handling secrets

- Configuration secrets are supplied through environment variables. `.env.example` documents the variables; `.env` is git-ignored.
- Never include real credentials in issues, pull requests, logs, or test fixtures.

## Data sensitivity

Error events can contain stack traces, messages, and context that include personal or confidential information. Operators are responsible for deciding what their applications send to TraceLite.
