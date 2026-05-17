# AGENTS.md

## Scope

This file is for coding agents collaborating in this repository.

- Project name: LocalMind Agent Platform / 智邻生活 Agent 平台
- This repository is a multi-module Java backend plus a Vue 3 frontend.
- Keep changes targeted and preserve runtime assumptions unless the user explicitly asks to change them.

## Project Overview

LocalMind Agent Platform is a local-life services platform that is being extended toward agent-driven recommendations and trip/shop planning.

Core business areas:

- merchant browsing and querying
- voucher issuing and high-concurrency voucher ordering
- user login and session handling
- nearby shop data based on stored shop coordinates
- blog/shop exploration content
- follow relationships and sign-in statistics
- cache management, distributed locking, rate limiting, delayed tasks, MQ, and sharding

## Repository Layout

- `pom.xml`: parent Maven aggregator
- `localmind-core-service`: main backend application and business logic
- `localmind-common`: shared constants, enums, exceptions, utilities
- `localmind-sharding`: ShardingSphere support
- `localmind-redisson-framework`: distributed lock, bloom filter, repeat-submit guard, delayed queue
- `localmind-redis-tool-framework`: Redis common utilities, cache, rate limiting
- `localmind-id-generator-framework`: distributed ID generator
- `localmind-mq-framework`: MQ abstractions and producer/consumer support
- `localmind-parameter`: parameter/config support
- `localmind-agent-web`: frontend app
- `sql`: local database initialization scripts
- `ops`: Docker-related files for middleware services
- `scripts`: local helper scripts

Some nested Maven module artifactIds still use the original internal coordinates. Do not rename them casually; they are dependency coordinates, not just display names.

## Key Entry Points

- Backend main class: `localmind-core-service/src/main/java/org/javaup/HmDianPingApplication.java`
- Backend config: `localmind-core-service/src/main/resources/application.yml`
- Local sharding config: `localmind-core-service/src/main/resources/shardingsphere.yaml`
- Frontend package: `localmind-agent-web/package.json`
- Middleware compose: `ops/docker-compose.dev.yml`

## Runtime Assumptions

Backend default configuration currently expects:

- HTTP port `8085`
- Redis at `127.0.0.1:6379`
- Kafka at `localhost:9092`
- MySQL at `127.0.0.1:3306`
- MySQL shards:
  - `hmdp_0`
  - `hmdp_1`

Keep the database names unchanged unless the user explicitly asks to migrate database configuration and SQL scripts.

## Commands

Run from the project root.

```bash
mvn clean package -DskipTests
mvn test -pl localmind-core-service
mvn test -pl localmind-core-service -Dtest=RedissonTest
mvn test -pl localmind-redis-tool-framework/hmdp-redis-rate-limit-framework -Dtest=RedisRateLimitHandlerTest
```

Frontend:

```bash
cd localmind-agent-web
pnpm install
pnpm dev
pnpm build
pnpm lint
pnpm format
```

Middleware:

```bat
scripts\dev-up.cmd
scripts\dev-logs.cmd
scripts\dev-down.cmd
```

## Working Rules

- Keep business logic in `localmind-core-service` unless the change is clearly framework-level.
- Respect the existing package structure under `org.javaup`.
- Do not silently change Redis host, Kafka address, datasource, sharding rules, database names, or server port.
- Before changing API calls, confirm the corresponding backend controller/service path in `localmind-core-service`.
- If a change touches voucher ordering, stock deduction, delayed tasks, rate limiting, cache rebuild, or sharding routes, read the surrounding framework code before editing.
