# ADR 0002: Issue fingerprinting

- **Status:** Accepted
- **Date:** 2026-10-10, updated 2026-10-12

## Context

Repeated occurrences of the same failure should be grouped into one issue so developers triage a problem once rather than once per event. Grouping must be deterministic and must not rely on the error message alone, because different failures can share a message and the same failure can produce messages with varying data (IDs, timestamps, values).

## Decision

`FingerprintGenerator` (`issue.application`) computes the fingerprint from the exception type and the normalised stack frames of the outermost exception. The message is never used.

### Algorithm

1. **Exception type.** `exceptionType` with leading and trailing whitespace removed.
2. **Frames.** The stack trace is read line by line (`\n` or `\r\n`). Reading stops at the first line that starts with `Caused by:` or `Suppressed:`, so only the outermost exception's frames count. Every line of the form `at <class>.<method>(<anything>)` is a frame; all other lines (the `Type: message` header, `... 3 more`, blank lines, other languages' formats) are skipped.
3. **Normalisation.** Each frame is reduced to its fully qualified `class.method`:
   - The location in parentheses (file name and line number) is dropped.
   - Module and class-loader prefixes (`java.base/`, `java.base@21.0.4/`, `app//`) are dropped.
   - Numbers the JVM or frameworks generate at runtime are removed: hidden-class suffixes (`/0x000001f0010a2b48`), lambda classes (`$$Lambda$14` becomes `$$Lambda`), lambda methods (`lambda$checkout$0` becomes `lambda$checkout`), JDK proxies (`jdk.proxy2.$Proxy45` becomes `jdk.proxy.$Proxy`), reflection accessors (`GeneratedMethodAccessor12`), Spring CGLIB classes (`$$SpringCGLIB$$0`, `$$EnhancerBySpringCGLIB$$a1b2c3`) and Hibernate proxies (`$HibernateProxy$Xy12Ab`).
4. **Recursion.** Consecutive identical frames are collapsed into one, so recursion depth does not split issues.
5. **Frame limit.** Only the first 30 distinct frames (after collapsing) are used. Frames further from the throw site, such as thread-pool or server entry points, do not change the fingerprint.
6. **Hash.** The type, then each frame in order, is written as `<length>:<text>\n` (length in Java characters) into SHA-256. The result is 64 lowercase hex characters. The length prefix keeps the parts unambiguous even if an exception type contains a newline.

Example: these two traces produce the same fingerprint, because they differ only in the message, a line number and a module prefix.

```text
java.lang.IllegalStateException: Cart 42 is empty
	at com.example.cart.Cart.checkout(Cart.java:42)
	at com.example.cart.CartController.checkout(CartController.java:18)
	at java.base/java.lang.Thread.run(Thread.java:1583)

java.lang.IllegalStateException: Cart 97 is empty
	at com.example.cart.Cart.checkout(Cart.java:57)
	at com.example.cart.CartController.checkout(CartController.java:18)
	at java.lang.Thread.run(Thread.java:1583)
```

The hashed input for both is:

```text
31:java.lang.IllegalStateException
30:com.example.cart.Cart.checkout
40:com.example.cart.CartController.checkout
20:java.lang.Thread.run
```

### Missing or incomplete stack traces

- No stack trace, a blank one, or one with no recognisable Java frames: the fingerprint is the hash of the exception type alone. All such events with the same type in one application and environment form one issue.
- A truncated last line (no closing parenthesis) is ignored, so a trace cut off mid-frame groups with the complete one as long as the earlier frames match.

### Scope

Application and environment are not part of the hash. They are the issue's scope: the unique constraint `issues_scope_fingerprint_uk` covers `(application, environment, fingerprint)`, so the same failure in production and staging is two issues.

### Answers to the earlier open questions

- **Which frames:** all frames of the outermost exception, up to 30 after collapsing recursion. Application and library frames are treated the same, because TraceLite has no reliable way to know which packages belong to an application.
- **Line numbers:** not included, so a redeploy that shifts code does not create new issues.
- **Missing or unparseable traces:** fall back to the exception type.
- **Environment:** part of the issue scope, not of the fingerprint.

## Limitations

The fingerprint is a heuristic. It cannot guarantee that grouped events share a root cause, or that separate issues have different causes.

- Only Java-style frames (`at class.method(...)`) are recognised. Traces from other languages fall back to the exception type, so all their events of one type group together.
- Different failures thrown from the same method with the same call path and type are grouped together (for example, two different `IllegalStateException` checks in one method).
- Renaming or moving a class or method, or changing the call path, creates a new issue.
- Wrapped exceptions are grouped by the outer exception's frames. A wrapper thrown from one place around many different causes becomes one issue.
- A trace cut off before the 30-frame limit (by the client or by the 65,536-character field limit) groups separately from the complete trace.
- Runtime-generated names not in the normalisation list (for example, other bytecode libraries) can still contain varying numbers and split issues.
- Changing this algorithm changes fingerprints, so events after the change start new issues instead of joining existing ones. Issues created with the Day 4 placeholder (a hash of the exception type alone) stop receiving events; new events for those failures start new issues.

## Consequences

Grouping ignores message text, line numbers and runtime-generated names, so repeated failures stay in one issue across deploys. The algorithm is covered by `FingerprintGeneratorTest`, including a fixed expected hash that fails if the algorithm changes by accident.
