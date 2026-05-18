# AGENTS.md

## Scope
This file is for coding agents collaborating in this repository.
- Project name: LocalMind Agent Platform
- Tech stack: Multi-module Java backend + Vue3 frontend
- Rule: Keep changes targeted, do not alter default runtime logic without explicit confirmation.

## Repository Layout
- `pom.xml`: Parent Maven aggregator
- `localmind-core-service`: Core business logic
- `localmind-common`: Public constants, utils, exceptions, enums
- `localmind-sharding`: ShardingSphere data sharding capability
- `localmind-redisson-framework`: Distributed lock, delay queue, repeat submit control
- `localmind-redis-tool-framework`: Redis cache, current limiting common tools
- `localmind-id-generator-framework`: Distributed ID generation
- `localmind-mq-framework`: Message queue unified abstraction
- `localmind-parameter`: Global parameter & configuration management
- `localmind-agent-web`: Vue3 frontend project
- `sql`: Database initialization scripts
- `ops`: Docker middleware deployment files
- `scripts`: Local development auxiliary scripts

## Working Rules
1. Core business code uniformly placed in `localmind-core-service`, framework-level modification can be placed in corresponding independent module.
2. Strictly follow existing `org.javaup` package hierarchy, do not adjust directory structure arbitrarily.
3. Prohibit unauthorized modification of Redis, Kafka, MySQL connection addresses, service port, sharding rules and database configuration.
4. Modify interface logic must confirm the existing Controller and Service definition first.
5. High-concurrency core logic such as coupon stock deduction, delayed task, current limiting, cache refresh needs to refer to original framework logic before modification.

## Change Archive Specification (Mandatory)
All **major structural changes, module addition/deletion, core logic reconstruction, framework capability adjustment, database structure modification, dependency upgrade** must be recorded in `codex.md` after completion.

### codex.md Standard Record Format

## Change Title | YYYY-MM-DD HH:mm | Core Purpose
### Affected Modules
Accurately write modified modules, packages, classes, configuration files

### Change Overview
Briefly describe the content, solved problems and realized functions

### Core Modification Points
List key logic, code, configuration and process changes

### Influence Scope
State impacts on existing interfaces, cache, tasks and other business modules

### Verification Points
Key test items for function confirmation

Minor adjustments such as comment optimization, format sorting, simple parameter tuning do not need to be archived.