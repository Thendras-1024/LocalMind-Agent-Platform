# AGENTS.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## MANDATORY FILE ENCODING RULES (NON-NEGOTIABLE)
1. All files MUST be encoded in UTF-8 (NO BOM)
2. UTF-16 / GBK / any other encoding is FORBIDDEN
3. Chinese characters MUST be written directly — NO \uXXXX unicode escapes
4. All file writes MUST use UTF-8 encoding

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---
## Scope
This file is for coding agents collaborating in this repository.
- Project name: LocalMind Agent Platform
- Tech stack: Multi-module Java backend + Vue3 frontend, Spring AI + Langgraph4j AI agent framework
- Rule: Keep changes targeted, do not alter default runtime logic without explicit confirmation.

## Repository Layout
- `localmind-core-service`: Core business logic
- `localmind-common`: Public constants, utils, exceptions, enums
- `localmind-sharding`: ShardingSphere data sharding capability
- `localmind-redisson-framework`: Distributed lock, delay queue, repeat submit control
- `localmind-redis-tool-framework`: Redis cache, current limiting common tools
- `localmind-id-generator-framework`: Distributed ID generation
- `localmind-mq-framework`: Message queue unified abstraction
- `localmind-parameter`: Global parameter & configuration management
- `localmind-agent-web`: Vue3 frontend project
