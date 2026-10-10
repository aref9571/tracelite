# Development

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 | Building and running the backend |
| Git | any recent | Source control |
| Docker | recent, with Compose v2 | PostgreSQL and integration tests (from Day 2) |
| Node.js | 22 LTS | Dashboard (from Day 11) |

Maven is not required: the Maven Wrapper (`./mvnw`) downloads Maven 3.9.11 on first use.

Check your JDK:

```bash
java -version
```

## Build

Run all commands from the repository root.

```bash
./mvnw verify
```

This compiles the code, runs the tests, and packages `backend/target/tracelite-backend.jar`.

## Run

```bash
./mvnw -pl backend spring-boot:run
```

or, after building:

```bash
java -jar backend/target/tracelite-backend.jar
```

The application listens on port 8080 by default. Stop it with `Ctrl+C`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |

Copy `.env.example` to `.env` to keep local values in one place. `.env` is git-ignored. Spring Boot does not read `.env` files itself; export the variables in your shell or let Docker Compose load them once it is introduced.

## Windows

Use `mvnw.cmd` in place of `./mvnw`.
