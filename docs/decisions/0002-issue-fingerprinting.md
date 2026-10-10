# ADR 0002: Issue fingerprinting

- **Status:** Proposed
- **Date:** 2026-10-10

## Context

Repeated occurrences of the same failure should be grouped into one issue so developers triage a problem once rather than once per event. Grouping must be deterministic and must not rely on the error message alone, because different failures can share a message and the same failure can produce messages with varying data (IDs, timestamps, values).

## Proposed direction

Derive a fingerprint from stable characteristics of an event:

- application name
- exception type
- a normalised subset of stack frames (for example class and method names, without line numbers or memory addresses)

Hash the normalised input with a stable algorithm (for example SHA-256) to produce the fingerprint. Scope issues by application and environment, and enforce uniqueness of the scoped fingerprint in the database.

## Open questions

To be settled when the algorithm is implemented:

- Which frames are relevant (all frames, the top N, or only application frames)?
- Whether line numbers are included.
- How to fingerprint events with missing or unparseable stack traces.
- Whether environment is part of the fingerprint or only part of the issue scope.

## Consequences

This decision will be finalised, with the exact algorithm, examples, and known limitations, when fingerprinting is implemented. A heuristic fingerprint cannot guarantee that grouped events share a root cause.
