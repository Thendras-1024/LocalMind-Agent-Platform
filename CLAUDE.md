# CLAUDE.md

This file provides repository guidance for coding agents.

## Project Overview

**LocalMind Agent Platform / 智邻生活 Agent 平台** is a Java backend and Vue frontend project for local-life services. It keeps existing merchant, voucher, order, Redis, Kafka, and sharding capabilities while evolving toward intelligent recommendation and planning agents.

## Build Commands

```bash
mvn clean package -DskipTests
mvn clean install -pl localmind-common -DskipTests
mvn test -pl localmind-core-service
mvn test -pl localmind-core-service -Dtest=RedissonTest
mvn clean install -DskipTests
```

## Architecture

```text
localmind-agent-platform
|-- localmind-common
|-- localmind-sharding
|-- localmind-core-service
|-- localmind-redisson-framework
|   |-- hmdp-redisson-service-framework
|   |-- hmdp-service-delay-queue-framework
|-- localmind-redis-tool-framework
|   |-- hmdp-redis-common-framework
|   |-- hmdp-redis-framework
|   |-- hmdp-redis-rate-limit-framework
|-- localmind-id-generator-framework
|-- localmind-mq-framework
|   |-- hmdp-mq-common-framework
|   |-- hmdp-mq-producer-framework
|   |-- hmdp-mq-consumer-framework
|-- localmind-parameter
|-- localmind-agent-web
```

Nested Maven module artifactIds still use their existing internal coordinates. They are dependency coordinates and should only be renamed in a dedicated full-coordinate migration.

## Tech Stack

- Java 17 + Spring Boot 3.5.4
- MyBatis-Plus 3.5.7
- ShardingSphere 5.3.2
- Redis + Redisson 3.52.0
- Kafka
- Sa-Token
- Vue 3 + Vite

## Critical Files

- Entry point: `localmind-core-service/src/main/java/org/javaup/HmDianPingApplication.java`
- Config: `localmind-core-service/src/main/resources/application.yml`
- Sharding config: `localmind-core-service/src/main/resources/shardingsphere.yaml`
- Frontend: `localmind-agent-web/package.json`

## Database

Database names are intentionally unchanged to avoid unnecessary runtime risk:

- `hmdp_0`
- `hmdp_1`

Do not rename these database names unless the user explicitly asks for database migration work.

## Module Notes

- `localmind-common` is a dependency for other modules.
- `localmind-core-service` is the main application that depends on the framework modules.
- Kafka is the active MQ implementation in the current code.
- Verification-code login is the primary supported login path.
