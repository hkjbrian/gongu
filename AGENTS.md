# Gongu Project - Codex Agent Instructions

## Project Basics

- Spring Boot 3.5, Java 25, MySQL 8.0, Redis 7.4
- Base package: `com.gongu.server`
- Source root: `src/main/java/com/gongu/server/`
- Architecture: 3-Layered + Rich Domain Model (ADR-002)
- Package layout: `domain/{auth,user,store,product,order,payment,notification}`, `global/{common,exception,config,security}`

## General Working Rules

- Follow the existing project architecture and local conventions before introducing new patterns.
- Prefer small, focused changes that preserve module boundaries.
- Treat `docs/adr/` and `docs/schema/ddl.sql` as source-of-truth material for architecture, exception handling, table names, columns, foreign keys, and indexes.
- Do not silently change unrelated files or revert user changes.

## Language

Unless the user explicitly asks otherwise, respond in Korean. For code review output, write all explanations in Korean while keeping severity labels (`Critical`, `Minor`) and file references (`file_path:line_number`) in the required format.

## Code Review Behavior

When acting as a code reviewer, use the `code-review-and-quality` skill if it is available.

The review must be strict, evidence-based, and findings-first. Focus on issues that could cause real defects or maintenance risk:

- correctness bugs and behavioral regressions
- security, authorization, data exposure, and input validation risks
- data loss, transaction, consistency, and concurrency risks
- schema mismatch, FK/index misuse, and persistence mapping problems
- exception handling violations against ADR-004
- architecture boundary violations against ADR-002
- performance risks such as N+1 queries, unbounded queries, and missing pagination
- missing or weak tests for changed behavior

Do not block on personal style preferences unless they create a concrete maintainability or correctness issue.

## Review Context Collection

Before producing a review, collect context in this order.

1. PR contents - understand what changed and why.

   ```bash
   gh pr view {PR_NUMBER}
   ```

2. Related issue - find issue numbers from PR body patterns such as `close #123`, `closes #123`, `fix #123`, or `resolves #123`, then read the issue.

   ```bash
   gh issue view {ISSUE_NUMBER}
   ```

3. Related documents - search under `docs/` for ADRs, specs, notes, or domain documents related to the issue and changed code.

4. Required review references - always read these before finalizing review findings:

   - `docs/review-guide.md`
   - `docs/adr/아키텍처_및_코드_컨벤션.md`
   - `docs/adr/예외_처리_전략.md`
   - `docs/schema/ddl.sql`

If any required context cannot be read, mention that limitation in the review.

## Review Output Format

Report findings first, ordered by severity.

Each issue must be classified as exactly one of:

- `Critical`: must be fixed before merge because it can cause a bug, security issue, data loss, broken contract, production regression, or serious architecture violation.
- `Minor`: should be fixed or considered, but does not by itself block merge.

Each issue must include a precise `file_path:line_number` reference because Claude parses this field to post GitHub inline comments.

Use this format for each finding:

```text
Critical: Short issue title
file_path:line_number
Explain the concrete problem, why it matters, and what change would address it.
```

```text
Minor: Short issue title
file_path:line_number
Explain the concrete problem, why it matters, and what change would address it.
```

If no issues are found, say that clearly and include any residual risk or unverified area, such as tests not run or context not available.

## Review Constraints

- Do not directly modify code when invoked as a reviewer.
- Do not post to GitHub directly. Claude is responsible for posting review comments.
- Do not include long summaries before findings.
- Do not invent line numbers. Inspect the relevant files and use exact line references.
