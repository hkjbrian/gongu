#!/usr/bin/env python3

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import sys
import textwrap
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


API_VERSION = "2022-11-28"
SUMMARY_MARKER = "<!-- codex-review:summary -->"
FINGERPRINT_RE = re.compile(r"codex-review:fingerprint=([0-9a-f]{12})")
SEVERITY_ORDER = ["critical", "high", "medium", "low"]
DEFAULT_BOT_LOGIN = "github-actions[bot]"

DEFAULT_CONFIG: dict[str, Any] = {
    "selection": {
        "skip_drafts": True,
        "title_include": [r"^\[(FEAT|FIX|REFACTOR|CHORE|TEST|DOCS)\]"],
        "title_exclude": [],
        "require_any_labels": [],
        "ignore_labels": ["skip-ai-review"],
        "force_labels": ["codex-review"],
    },
    "review": {
        "max_files": 15,
        "max_patch_chars": 8000,
        "max_file_chars": 12000,
        "max_total_chars": 100000,
        "fail_on_severities": ["critical", "high", "medium"],
        "auto_approve_when_clean": False,
        "resolve_threads_on_rereview": True,
        "request_changes_review": False,
        "summary_header": "## Codex Review",
    },
    "openai": {
        "model": "gpt-5.4-mini",
        "max_output_tokens": 4000,
    },
    "documents": [
        "docs/code-review.md",
        "CONTRIBUTING.md",
        "docs/adr/아키텍처_및_코드_컨벤션.md",
        ".github/pull_request_template.md",
    ],
}


SYSTEM_INSTRUCTIONS = """
You are Codex, a careful senior pull request reviewer.

Review only the code that is relevant to the current pull request. Follow the repository documents as the source of truth for conventions. Prefer under-reporting to speculative comments.

Rules:
- Report only actionable issues with a clear reason and likely impact.
- Use line_comment=true only if the issue can be anchored to a changed line in the diff.
- Put cross-cutting notes, follow-up questions, or uncertain concerns in general_comments instead of findings.
- Keep titles short and specific.
- Keep anchor stable across commits by using a symbol name, invariant name, state transition name, or test scenario name.
- If the PR is clean, return no findings.
""".strip()


def eprint(message: str) -> None:
    print(message, file=sys.stderr)


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    merged = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def load_config() -> dict[str, Any]:
    path = Path(".github/codex-review.json")
    if not path.exists():
        return DEFAULT_CONFIG
    return deep_merge(DEFAULT_CONFIG, read_json(path))


def github_request(
    method: str,
    path: str,
    *,
    token: str,
    data: dict[str, Any] | None = None,
    accept: str = "application/vnd.github+json",
    is_graphql: bool = False,
) -> Any:
    if is_graphql:
        url = "https://api.github.com/graphql"
    else:
        url = f"https://api.github.com{path}"
    payload = None
    if data is not None:
        payload = json.dumps(data).encode("utf-8")
    request = urllib.request.Request(url, method=method, data=payload)
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Accept", accept)
    request.add_header("X-GitHub-Api-Version", API_VERSION)
    if payload is not None:
        request.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(request) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"GitHub API {method} {path if not is_graphql else '/graphql'} failed: "
            f"{exc.code} {error_body}"
        ) from exc


def openai_request(data: dict[str, Any], *, token: str) -> Any:
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        method="POST",
        data=json.dumps(data).encode("utf-8"),
    )
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OpenAI Responses API failed: {exc.code} {error_body}") from exc


def paginate_rest(path: str, *, token: str) -> list[dict[str, Any]]:
    page = 1
    items: list[dict[str, Any]] = []
    while True:
        separator = "&" if "?" in path else "?"
        payload = github_request(
            "GET",
            f"{path}{separator}per_page=100&page={page}",
            token=token,
        )
        if not payload:
            break
        items.extend(payload)
        if len(payload) < 100:
            break
        page += 1
    return items


def get_bot_login() -> str:
    return os.getenv("CODEX_REVIEW_BOT_LOGIN", DEFAULT_BOT_LOGIN)


def normalize_whitespace(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def normalize_text(text: str) -> str:
    return normalize_whitespace(text).lower()


def clip(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + "\n[truncated]\n"


def number_lines(text: str) -> str:
    return "\n".join(f"{index:4d} | {line}" for index, line in enumerate(text.splitlines(), start=1))


def parse_changed_lines(patch: str | None) -> set[int]:
    if not patch:
        return set()
    changed: set[int] = set()
    current_new = 0
    for line in patch.splitlines():
        if line.startswith("@@"):
            match = re.search(r"\+(\d+)(?:,(\d+))?", line)
            if not match:
                continue
            current_new = int(match.group(1))
            continue
        if line.startswith("+++"):
            continue
        if line.startswith("+"):
            changed.add(current_new)
            current_new += 1
            continue
        if line.startswith("-"):
            continue
        current_new += 1
    return changed


def make_fingerprint(path: str, category: str, anchor: str, title: str) -> str:
    material = "||".join(
        [
            normalize_text(path),
            normalize_text(category),
            normalize_text(anchor),
            normalize_text(title),
        ]
    )
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def should_review(pr: dict[str, Any], config: dict[str, Any]) -> tuple[bool, str]:
    selection = config["selection"]
    title = pr["title"]
    labels = {label["name"] for label in pr.get("labels", [])}

    if labels & set(selection["force_labels"]):
        return True, "forced by label"
    if selection["skip_drafts"] and pr.get("draft"):
        return False, "draft pull request"
    if labels & set(selection["ignore_labels"]):
        return False, "ignored by label"
    if selection["require_any_labels"] and not (labels & set(selection["require_any_labels"])):
        return False, "required labels missing"
    if selection["title_exclude"] and any(re.search(pattern, title) for pattern in selection["title_exclude"]):
        return False, "excluded by title rule"
    if selection["title_include"] and not any(
        re.search(pattern, title) for pattern in selection["title_include"]
    ):
        return False, "title rule did not match"
    return True, "selection rules matched"


def load_documents(paths: list[str]) -> list[tuple[str, str]]:
    loaded: list[tuple[str, str]] = []
    for raw_path in paths:
        path = Path(raw_path)
        if not path.exists():
            continue
        loaded.append((raw_path, path.read_text(encoding="utf-8")))
    return loaded


def fetch_pull_request(owner: str, repo: str, number: int, token: str) -> dict[str, Any]:
    return github_request("GET", f"/repos/{owner}/{repo}/pulls/{number}", token=token)


def fetch_pull_files(owner: str, repo: str, number: int, token: str) -> list[dict[str, Any]]:
    return paginate_rest(f"/repos/{owner}/{repo}/pulls/{number}/files", token=token)


def fetch_file_content(owner: str, repo: str, path: str, ref: str, token: str) -> str | None:
    quoted = urllib.parse.quote(path, safe="/")
    payload = github_request(
        "GET",
        f"/repos/{owner}/{repo}/contents/{quoted}?ref={ref}",
        token=token,
    )
    if isinstance(payload, list):
        return None
    if payload.get("encoding") != "base64" or "content" not in payload:
        return None
    raw = payload["content"].replace("\n", "")
    return base64.b64decode(raw).decode("utf-8", errors="replace")


def fetch_review_threads(owner: str, repo: str, number: int, token: str) -> list[dict[str, Any]]:
    threads: list[dict[str, Any]] = []
    cursor = None
    while True:
        payload = github_request(
            "POST",
            "",
            token=token,
            is_graphql=True,
            data={
                "query": """
query($owner: String!, $repo: String!, $number: Int!, $after: String) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      reviewThreads(first: 100, after: $after) {
        nodes {
          id
          isResolved
          isOutdated
          path
          line
          comments(first: 20) {
            nodes {
              id
              databaseId
              body
              author {
                login
              }
            }
          }
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
    }
  }
}
""",
                "variables": {
                    "owner": owner,
                    "repo": repo,
                    "number": number,
                    "after": cursor,
                },
            },
        )
        section = payload["data"]["repository"]["pullRequest"]["reviewThreads"]
        threads.extend(section["nodes"])
        if not section["pageInfo"]["hasNextPage"]:
            break
        cursor = section["pageInfo"]["endCursor"]
    return threads


def fetch_issue_comments(owner: str, repo: str, number: int, token: str) -> list[dict[str, Any]]:
    return paginate_rest(f"/repos/{owner}/{repo}/issues/{number}/comments", token=token)


def fetch_reviews(owner: str, repo: str, number: int, token: str) -> list[dict[str, Any]]:
    return paginate_rest(f"/repos/{owner}/{repo}/pulls/{number}/reviews", token=token)


def extract_thread_index(threads: list[dict[str, Any]], bot_login: str) -> dict[str, dict[str, Any]]:
    indexed: dict[str, dict[str, Any]] = {}
    for thread in threads:
        for comment in thread["comments"]["nodes"]:
            login = ((comment.get("author") or {}).get("login")) or ""
            if login != bot_login:
                continue
            match = FINGERPRINT_RE.search(comment.get("body", ""))
            if not match:
                continue
            indexed[match.group(1)] = {
                "thread_id": thread["id"],
                "resolved": thread["isResolved"],
                "comment_database_id": comment["databaseId"],
                "path": thread.get("path") or "",
                "line": thread.get("line"),
            }
            break
    return indexed


def upsert_summary_comment(
    owner: str,
    repo: str,
    number: int,
    body: str,
    token: str,
    bot_login: str,
) -> None:
    comments = fetch_issue_comments(owner, repo, number, token)
    existing = None
    for comment in comments:
        author = (comment.get("user") or {}).get("login")
        if author == bot_login and SUMMARY_MARKER in comment.get("body", ""):
            existing = comment
    if existing:
        github_request(
            "PATCH",
            f"/repos/{owner}/{repo}/issues/comments/{existing['id']}",
            token=token,
            data={"body": body},
        )
        return
    github_request(
        "POST",
        f"/repos/{owner}/{repo}/issues/{number}/comments",
        token=token,
        data={"body": body},
    )


def resolve_thread(thread_id: str, token: str) -> None:
    github_request(
        "POST",
        "",
        token=token,
        is_graphql=True,
        data={
            "query": """
mutation($threadId: ID!) {
  resolveReviewThread(input: { threadId: $threadId }) {
    thread {
      id
    }
  }
}
""",
            "variables": {"threadId": thread_id},
        },
    )


def unresolve_thread(thread_id: str, token: str) -> None:
    github_request(
        "POST",
        "",
        token=token,
        is_graphql=True,
        data={
            "query": """
mutation($threadId: ID!) {
  unresolveReviewThread(input: { threadId: $threadId }) {
    thread {
      id
    }
  }
}
""",
            "variables": {"threadId": thread_id},
        },
    )


def reply_to_review_comment(owner: str, repo: str, number: int, comment_id: int, body: str, token: str) -> None:
    github_request(
        "POST",
        f"/repos/{owner}/{repo}/pulls/{number}/comments/{comment_id}/replies",
        token=token,
        data={"body": body},
    )


def create_review(
    owner: str,
    repo: str,
    number: int,
    token: str,
    *,
    body: str,
    event: str,
    comments: list[dict[str, Any]] | None = None,
) -> None:
    payload: dict[str, Any] = {"body": body, "event": event}
    if comments:
        payload["comments"] = comments
    github_request(
        "POST",
        f"/repos/{owner}/{repo}/pulls/{number}/reviews",
        token=token,
        data=payload,
    )


def extract_response_text(response: dict[str, Any]) -> str:
    texts: list[str] = []
    for item in response.get("output", []):
        if item.get("type") != "message":
            continue
        for content in item.get("content", []):
            if "text" in content:
                texts.append(content["text"])
    if not texts:
        raise RuntimeError(f"No text output found in OpenAI response: {json.dumps(response)[:1200]}")
    return "".join(texts)


def build_prompt(
    pr: dict[str, Any],
    files: list[dict[str, Any]],
    docs: list[tuple[str, str]],
    config: dict[str, Any],
) -> str:
    review_config = config["review"]
    sections: list[str] = []

    sections.append(
        "\n".join(
            [
                "# Pull request",
                f"Title: {pr['title']}",
                f"Number: {pr['number']}",
                f"Base: {pr['base']['ref']}",
                f"Head: {pr['head']['ref']}",
                f"Head SHA: {pr['head']['sha']}",
                "Labels: "
                + (", ".join(label["name"] for label in pr.get("labels", [])) or "(none)"),
                "Body:",
                pr.get("body") or "(empty)",
            ]
        )
    )

    if docs:
        doc_sections = ["# Repository review documents"]
        for path, content in docs:
            doc_sections.append(f"## {path}\n{content}")
        sections.append("\n\n".join(doc_sections))

    file_sections = ["# Changed files"]
    total_chars = sum(len(section) for section in sections)
    for file_info in files[: review_config["max_files"]]:
        patch = clip(file_info.get("patch") or "(no patch available)", review_config["max_patch_chars"])
        content = clip(file_info.get("head_content") or "(content unavailable)", review_config["max_file_chars"])
        changed_lines = sorted(file_info.get("changed_lines") or [])
        changed_line_text = ", ".join(str(line) for line in changed_lines[:80]) or "(none)"
        section = "\n".join(
            [
                f"## {file_info['filename']}",
                f"Status: {file_info['status']}",
                f"Changed lines on new side: {changed_line_text}",
                "Patch:",
                "```diff",
                patch,
                "```",
                "Head content with line numbers:",
                "```text",
                number_lines(content),
                "```",
            ]
        )
        if total_chars + len(section) > review_config["max_total_chars"]:
            file_sections.append("[remaining changed files omitted due to prompt budget]")
            break
        file_sections.append(section)
        total_chars += len(section)
    sections.append("\n\n".join(file_sections))

    sections.append(
        textwrap.dedent(
            """
            # Output requirements
            - Return JSON only.
            - findings must contain only actionable issues.
            - line_comment=true only if the issue maps to a changed line listed for that file.
            - Use severity from {critical, high, medium, low}.
            - Use category from {bug, test, design, convention, security, performance, maintainability}.
            - anchor must be stable and specific, like a method name, invariant, state transition, or test scenario.
            - body should be 1 paragraph in Korean and explain reason plus likely impact.
            - general_comments should be concise Korean strings for the summary comment.
            """
        ).strip()
    )

    return "\n\n".join(sections)


def call_reviewer(prompt: str, config: dict[str, Any], token: str) -> dict[str, Any]:
    schema = {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "summary": {"type": "string"},
            "overall": {"type": "string", "enum": ["clean", "needs_changes", "comment_only"]},
            "findings": {
                "type": "array",
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "path": {"type": "string"},
                        "line": {"type": "integer", "minimum": 1},
                        "severity": {"type": "string", "enum": SEVERITY_ORDER},
                        "category": {
                            "type": "string",
                            "enum": [
                                "bug",
                                "test",
                                "design",
                                "convention",
                                "security",
                                "performance",
                                "maintainability",
                            ],
                        },
                        "title": {"type": "string"},
                        "body": {"type": "string"},
                        "anchor": {"type": "string"},
                        "line_comment": {"type": "boolean"},
                        "confidence": {"type": "string", "enum": ["low", "medium", "high"]},
                    },
                    "required": [
                        "path",
                        "line",
                        "severity",
                        "category",
                        "title",
                        "body",
                        "anchor",
                        "line_comment",
                        "confidence",
                    ],
                },
            },
            "general_comments": {
                "type": "array",
                "items": {"type": "string"},
            },
        },
        "required": ["summary", "overall", "findings", "general_comments"],
    }

    model = os.getenv("OPENAI_MODEL") or config["openai"]["model"]
    response = openai_request(
        {
            "model": model,
            "instructions": SYSTEM_INSTRUCTIONS,
            "input": prompt,
            "max_output_tokens": config["openai"]["max_output_tokens"],
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "codex_pr_review",
                    "schema": schema,
                    "strict": True,
                }
            },
        },
        token=token,
    )
    return json.loads(extract_response_text(response))


def normalize_findings(
    model_output: dict[str, Any],
    file_index: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    seen: set[str] = set()

    for finding in model_output.get("findings", []):
        path = finding["path"]
        if path not in file_index:
            continue
        anchor = normalize_whitespace(finding["anchor"])
        title = normalize_whitespace(finding["title"])
        body = normalize_whitespace(finding["body"])
        fingerprint = make_fingerprint(path, finding["category"], anchor, title)
        if fingerprint in seen:
            continue
        file_info = file_index[path]
        line = int(finding["line"])
        can_inline = line in file_info["changed_lines"] and bool(finding["line_comment"])
        findings.append(
            {
                "fingerprint": fingerprint,
                "path": path,
                "line": line,
                "severity": finding["severity"],
                "category": finding["category"],
                "title": title,
                "body": body,
                "anchor": anchor,
                "line_comment": can_inline,
                "confidence": finding["confidence"],
            }
        )
        seen.add(fingerprint)

    findings.sort(key=lambda item: (SEVERITY_ORDER.index(item["severity"]), item["path"], item["line"]))
    return findings


def count_by_severity(findings: list[dict[str, Any]]) -> dict[str, int]:
    counts = {severity: 0 for severity in SEVERITY_ORDER}
    for finding in findings:
        counts[finding["severity"]] += 1
    return counts


def build_summary_body(
    *,
    config: dict[str, Any],
    pr: dict[str, Any],
    should_run: bool,
    reason: str,
    model_output: dict[str, Any] | None,
    findings: list[dict[str, Any]],
    docs: list[tuple[str, str]],
) -> str:
    header = config["review"]["summary_header"]
    head_sha = pr["head"]["sha"][:7]
    doc_list = ", ".join(path for path, _ in docs) or "(none)"

    lines = [SUMMARY_MARKER, header, "", f"- Head commit: `{head_sha}`", f"- Documents: {doc_list}"]
    if not should_run:
        lines.extend(["- Status: `skipped`", f"- Reason: {reason}"])
        return "\n".join(lines)

    counts = count_by_severity(findings)
    lines.extend(
        [
            f"- Status: `{model_output['overall'] if model_output else 'needs_changes'}`",
            f"- Reason: {reason}",
            (
                "- Outstanding findings: "
                f"{len(findings)} "
                f"(critical {counts['critical']}, high {counts['high']}, "
                f"medium {counts['medium']}, low {counts['low']})"
            ),
            "",
            "### Summary",
            model_output["summary"] if model_output else "Review did not run.",
        ]
    )

    if findings:
        lines.append("")
        lines.append("### Outstanding findings")
        for finding in findings:
            lines.append(
                f"- [{finding['severity']}] [{finding['category']}] "
                f"`{finding['path']}:{finding['line']}` {finding['title']}"
            )

    general_comments = model_output.get("general_comments", []) if model_output else []
    if general_comments:
        lines.append("")
        lines.append("### General notes")
        for comment in general_comments:
            lines.append(f"- {normalize_whitespace(comment)}")

    lines.append("")
    lines.append("### Automation notes")
    lines.append("- Codex fingerprints inline findings and resolves matching threads automatically on re-review.")
    return "\n".join(lines)


def latest_bot_approval_for_head(
    reviews: list[dict[str, Any]],
    *,
    bot_login: str,
    head_sha: str,
) -> bool:
    for review in reversed(reviews):
        if (review.get("user") or {}).get("login") != bot_login:
            continue
        if review.get("state") != "APPROVED":
            continue
        if review.get("commit_id") == head_sha:
            return True
    return False


def main() -> int:
    github_token = os.getenv("GITHUB_TOKEN")
    openai_token = os.getenv("OPENAI_API_KEY")
    event_path = os.getenv("GITHUB_EVENT_PATH")
    bot_login = get_bot_login()

    if not github_token:
        raise RuntimeError("GITHUB_TOKEN is required.")
    if not event_path:
        raise RuntimeError("GITHUB_EVENT_PATH is required.")
    if not openai_token:
        raise RuntimeError("OPENAI_API_KEY secret is required.")

    config = load_config()
    event = read_json(Path(event_path))
    repo_full_name = event["repository"]["full_name"]
    owner, repo = repo_full_name.split("/", 1)
    pr_number = event["pull_request"]["number"]

    pr = fetch_pull_request(owner, repo, pr_number, github_token)
    should_run, reason = should_review(pr, config)
    docs = load_documents(config["documents"])

    if not should_run:
        summary_body = build_summary_body(
            config=config,
            pr=pr,
            should_run=False,
            reason=reason,
            model_output=None,
            findings=[],
            docs=docs,
        )
        upsert_summary_comment(owner, repo, pr_number, summary_body, github_token, bot_login)
        return 0

    files = fetch_pull_files(owner, repo, pr_number, github_token)
    head_repo = pr["head"].get("repo") or {}
    head_owner = ((head_repo.get("owner") or {}).get("login")) or owner
    head_repo_name = head_repo.get("name") or repo
    reviewable_files: list[dict[str, Any]] = []
    for file_info in files:
        if file_info.get("status") == "removed":
            continue
        if file_info.get("patch") is None:
            continue
        head_content = fetch_file_content(
            head_owner,
            head_repo_name,
            file_info["filename"],
            pr["head"]["sha"],
            github_token,
        )
        file_copy = dict(file_info)
        file_copy["head_content"] = head_content or ""
        file_copy["changed_lines"] = parse_changed_lines(file_info.get("patch"))
        reviewable_files.append(file_copy)

    file_index = {item["filename"]: item for item in reviewable_files}
    prompt = build_prompt(pr, reviewable_files, docs, config)
    model_output = call_reviewer(prompt, config, openai_token)
    findings = normalize_findings(model_output, file_index)

    threads = fetch_review_threads(owner, repo, pr_number, github_token)
    thread_index = extract_thread_index(threads, bot_login)

    active_fingerprints = {finding["fingerprint"] for finding in findings}
    for fingerprint, thread in thread_index.items():
        if fingerprint in active_fingerprints and thread["resolved"]:
            unresolve_thread(thread["thread_id"], github_token)
            reply_to_review_comment(
                owner,
                repo,
                pr_number,
                thread["comment_database_id"],
                f"이 이슈는 `{pr['head']['sha'][:7]}` 기준으로 다시 재현되어 스레드를 reopen 합니다.",
                github_token,
            )
        if fingerprint not in active_fingerprints and not thread["resolved"] and config["review"]["resolve_threads_on_rereview"]:
            reply_to_review_comment(
                owner,
                repo,
                pr_number,
                thread["comment_database_id"],
                f"현재 `{pr['head']['sha'][:7]}` 기준으로 같은 fingerprint가 재검출되지 않아 resolve 처리합니다.",
                github_token,
            )
            resolve_thread(thread["thread_id"], github_token)

    new_inline_comments: list[dict[str, Any]] = []
    for finding in findings:
        existing = thread_index.get(finding["fingerprint"])
        body = (
            f"**[{finding['severity'].upper()}] {finding['title']}**\n\n"
            f"{finding['body']}\n\n"
            f"<!-- codex-review:fingerprint={finding['fingerprint']} -->"
        )
        if existing:
            continue
        if not finding["line_comment"]:
            continue
        new_inline_comments.append(
            {
                "path": finding["path"],
                "line": finding["line"],
                "side": "RIGHT",
                "body": body,
            }
        )

    summary_body = build_summary_body(
        config=config,
        pr=pr,
        should_run=True,
        reason=reason,
        model_output=model_output,
        findings=findings,
        docs=docs,
    )
    upsert_summary_comment(owner, repo, pr_number, summary_body, github_token, bot_login)

    if new_inline_comments:
        event_name = "COMMENT"
        if config["review"]["request_changes_review"] and findings:
            event_name = "REQUEST_CHANGES"
        create_review(
            owner,
            repo,
            pr_number,
            github_token,
            body=f"Codex가 `{pr['head']['sha'][:7]}` 기준으로 새로 검출한 이슈를 남깁니다.",
            event=event_name,
            comments=new_inline_comments,
        )

    reviews = fetch_reviews(owner, repo, pr_number, github_token)
    auto_approve = config["review"]["auto_approve_when_clean"]
    if auto_approve and not findings and not latest_bot_approval_for_head(
        reviews,
        bot_login=bot_login,
        head_sha=pr["head"]["sha"],
    ):
        create_review(
            owner,
            repo,
            pr_number,
            github_token,
            body=f"Codex 기준에서 미해결 이슈가 없어 `{pr['head']['sha'][:7]}` 을 approve 합니다.",
            event="APPROVE",
        )

    fail_on = set(config["review"]["fail_on_severities"])
    should_fail = any(finding["severity"] in fail_on for finding in findings)
    return 1 if should_fail else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # pragma: no cover - workflow level reporting
        eprint(f"codex review failed: {exc}")
        raise
