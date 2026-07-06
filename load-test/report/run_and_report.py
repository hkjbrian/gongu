#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import requests
from dotenv import load_dotenv


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
REPORTS_DIR = PROJECT_ROOT / "load-test" / "reports"
PROMETHEUS_SCRAPE_BUFFER_SECONDS = 30
GRAFANA_RENDER_WIDTH = 1000
GRAFANA_RENDER_HEIGHT = 500
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
NOTION_API_BASE_URL = "https://api.notion.com/v1"
NOTION_VERSION = "2026-03-11"
NOTION_RICH_TEXT_CONTENT_LIMIT = 2000
NOTION_RICH_TEXT_ITEMS_PER_CODE_BLOCK = 100

GRAFANA_CAPTURE_PANELS: tuple[tuple[str, int, str], ...] = (
    ("gongu-service-overview", 15, "HTTP 요청률 & 에러율"),
    ("gongu-service-overview", 16, "HTTP 응답 시간 p95"),
    ("gongu-service-overview", 17, "HikariCP 활성 커넥션"),
    ("gongu-service-overview", 14, "JVM 힙 사용량"),
    ("gongu-service-overview", 19, "Order 생성 전체 소요시간"),
    ("549c2bf8936f7767ea6ac47c47b00f2a", 13, "Current QPS"),
    ("549c2bf8936f7767ea6ac47c47b00f2a", 92, "MySQL Connections"),
    ("549c2bf8936f7767ea6ac47c47b00f2a", 48, "MySQL Slow Queries"),
    ("549c2bf8936f7767ea6ac47c47b00f2a", 51, "InnoDB Buffer Pool"),
    ("549c2bf8936f7767ea6ac47c47b00f2a", 32, "MySQL Table Locks"),
)

GRAFANA_DASHBOARD_SLUGS = {
    "gongu-service-overview": "gongu-service-overview",
    "549c2bf8936f7767ea6ac47c47b00f2a": "mysql-exporter-quickstart-and-dashboard",
}


@dataclass(frozen=True)
class K6RunResult:
    scenario: str
    condition: str
    passed: bool
    exit_code: int
    stdout: str
    started_at_epoch_ms: int
    ended_at_epoch_ms: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a k6 load-test scenario and prepare captured results for reporting."
    )
    parser.add_argument(
        "scenario",
        help="Scenario file basename under load-test/scenarios, without .js (example: 07-order-tps)",
    )
    parser.add_argument(
        "--condition",
        required=True,
        help="Human-readable description of the test condition.",
    )
    return parser.parse_args()


def utc_epoch_ms() -> int:
    return int(time.time() * 1000)


def utc_iso_from_epoch_ms(epoch_ms: int) -> str:
    return (
        datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


def normalize_scenario_name(scenario: str) -> str:
    if not scenario or "/" in scenario or "\\" in scenario:
        raise ValueError("scenario must be a file basename, for example: 07-order-tps")

    return scenario[:-3] if scenario.endswith(".js") else scenario


def scenario_path(scenario: str) -> Path:
    return PROJECT_ROOT / "load-test" / "scenarios" / f"{scenario}.js"


def build_k6_command(scenario: str) -> list[str]:
    return [
        "docker",
        "compose",
        "--profile",
        "k6",
        "run",
        "--rm",
        "k6",
        "run",
        f"/scripts/scenarios/{scenario}.js",
    ]


def run_k6(scenario: str) -> tuple[int, str]:
    command = build_k6_command(scenario)
    print(f"[run] {' '.join(command)}", flush=True)

    process = subprocess.Popen(
        command,
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )

    output_lines: list[str] = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="", flush=True)
        output_lines.append(line)

    return process.wait(), "".join(output_lines)


def save_k6_output_backup(started_at_epoch_ms: int, stdout: str) -> Path:
    backup_dir = REPORTS_DIR / utc_iso_from_epoch_ms(started_at_epoch_ms)
    backup_dir.mkdir(parents=True, exist_ok=True)

    output_file = backup_dir / "k6-output.txt"
    output_file.write_text(stdout, encoding="utf-8")
    return output_file


def wait_for_metrics_buffer() -> None:
    print(
        f"[wait] Waiting {PROMETHEUS_SCRAPE_BUFFER_SECONDS}s for metrics scrape buffer...",
        flush=True,
    )
    time.sleep(PROMETHEUS_SCRAPE_BUFFER_SECONDS)


def capture_grafana_screenshots(run_data: K6RunResult) -> list[Path]:
    grafana_url = os.environ.get("GRAFANA_URL", "http://localhost:3001").rstrip("/")
    grafana_user = os.environ.get("GRAFANA_USER", "admin")
    grafana_password = os.environ.get("GRAFANA_PASSWORD", "admin")
    from_ms = run_data.started_at_epoch_ms
    to_ms = run_data.ended_at_epoch_ms + (PROMETHEUS_SCRAPE_BUFFER_SECONDS * 1000)

    output_dir = REPORTS_DIR / utc_iso_from_epoch_ms(run_data.started_at_epoch_ms)
    output_dir.mkdir(parents=True, exist_ok=True)

    screenshot_paths: list[Path] = []
    for index, (dashboard_uid, panel_id, panel_name) in enumerate(
        GRAFANA_CAPTURE_PANELS, start=1
    ):
        dashboard_slug = GRAFANA_DASHBOARD_SLUGS[dashboard_uid]
        render_url = (
            f"{grafana_url}/render/d-solo/{dashboard_uid}/{dashboard_slug}"
            f"?panelId={panel_id}"
            f"&from={from_ms}"
            f"&to={to_ms}"
            f"&width={GRAFANA_RENDER_WIDTH}"
            f"&height={GRAFANA_RENDER_HEIGHT}"
            "&tz=Asia%2FSeoul"
        )
        print(f"[capture] Rendering panel-{index}: {panel_name}", flush=True)

        response = requests.get(
            render_url,
            auth=(grafana_user, grafana_password),
            timeout=60,
        )
        if response.status_code != 200:
            body_preview = response.text[:300].replace("\n", " ")
            raise RuntimeError(
                f"Grafana render failed for panel-{index} ({panel_name}): "
                f"HTTP {response.status_code}: {body_preview}"
            )
        if not response.content.startswith(PNG_SIGNATURE):
            content_type = response.headers.get("content-type", "unknown")
            raise RuntimeError(
                f"Grafana render response is not a PNG for panel-{index} "
                f"({panel_name}): content-type={content_type}"
            )

        screenshot_path = output_dir / f"panel-{index}.png"
        screenshot_path.write_bytes(response.content)
        screenshot_paths.append(screenshot_path)

    return screenshot_paths


def notion_headers(notion_token: str, *, json_content: bool = True) -> dict[str, str]:
    headers = {
        "Authorization": f"Bearer {notion_token}",
        "Accept": "application/json",
        "Notion-Version": NOTION_VERSION,
    }
    if json_content:
        headers["Content-Type"] = "application/json"

    return headers


def notion_api_error_message(response: requests.Response) -> str:
    try:
        body = response.json()
    except json.JSONDecodeError:
        body_preview = response.text[:300].replace("\n", " ")
        return f"HTTP {response.status_code}: {body_preview}"

    error_code = body.get("code", "unknown_error")
    message = body.get("message", "")
    return f"HTTP {response.status_code}: code={error_code}, message={message}"


def ensure_notion_success(response: requests.Response, action: str) -> None:
    if 200 <= response.status_code < 300:
        return

    raise RuntimeError(f"{action} failed: {notion_api_error_message(response)}")


def notion_rich_text_chunks(text: str) -> list[dict[str, dict[str, str]]]:
    if text == "":
        text = "(no k6 output)"

    return [
        {
            "type": "text",
            "text": {
                "content": text[
                    index : index + NOTION_RICH_TEXT_CONTENT_LIMIT
                ]
            },
        }
        for index in range(0, len(text), NOTION_RICH_TEXT_CONTENT_LIMIT)
    ]


def notion_code_blocks(stdout: str) -> list[dict[str, object]]:
    rich_text_chunks = notion_rich_text_chunks(stdout)
    return [
        {
            "object": "block",
            "type": "code",
            "code": {
                "caption": [],
                "rich_text": rich_text_chunks[
                    index : index + NOTION_RICH_TEXT_ITEMS_PER_CODE_BLOCK
                ],
                "language": "plain text",
            },
        }
        for index in range(0, len(rich_text_chunks), NOTION_RICH_TEXT_ITEMS_PER_CODE_BLOCK)
    ]


def notion_image_block(file_upload_id: str) -> dict[str, object]:
    return {
        "object": "block",
        "type": "image",
        "image": {
            "caption": [],
            "type": "file_upload",
            "file_upload": {"id": file_upload_id},
        },
    }


def upload_png_to_notion(
    screenshot_path: Path, notion_token: str, upload_index: int
) -> str:
    if not screenshot_path.is_file():
        raise FileNotFoundError(f"Screenshot file not found: {screenshot_path}")
    if not screenshot_path.read_bytes().startswith(PNG_SIGNATURE):
        raise RuntimeError(f"Screenshot file is not a PNG: {screenshot_path}")

    create_response = requests.post(
        f"{NOTION_API_BASE_URL}/file_uploads",
        headers=notion_headers(notion_token),
        json={"filename": screenshot_path.name, "content_type": "image/png"},
        timeout=30,
    )
    ensure_notion_success(
        create_response,
        f"Notion file upload object creation for panel-{upload_index}",
    )
    file_upload = create_response.json()
    file_upload_id = file_upload["id"]
    upload_url = file_upload["upload_url"]

    with screenshot_path.open("rb") as screenshot_file:
        upload_response = requests.post(
            upload_url,
            headers=notion_headers(notion_token, json_content=False),
            files={"file": (screenshot_path.name, screenshot_file, "image/png")},
            timeout=60,
        )
    ensure_notion_success(
        upload_response,
        f"Notion file content upload for panel-{upload_index}",
    )

    uploaded_file = upload_response.json()
    if uploaded_file.get("status") != "uploaded":
        raise RuntimeError(
            f"Notion file content upload for panel-{upload_index} did not finish: "
            f"status={uploaded_file.get('status')}"
        )

    return file_upload_id


def notion_page_url(page_id: str, block_id: str | None = None) -> str:
    page_url = f"https://www.notion.so/{page_id.replace('-', '')}"
    if block_id is None:
        return page_url

    return f"{page_url}#{block_id.replace('-', '')}"


def upload_to_notion(run_data: K6RunResult, screenshot_paths: list[Path]) -> None:
    notion_token = os.environ.get("NOTION_TOKEN")
    notion_page_id = os.environ.get("NOTION_PAGE_ID")
    if not notion_token:
        raise RuntimeError("NOTION_TOKEN is not set")
    if not notion_page_id:
        raise RuntimeError("NOTION_PAGE_ID is not set")

    file_upload_ids: list[str] = []
    for index, screenshot_path in enumerate(screenshot_paths, start=1):
        print(
            f"[notion] Uploading screenshot {index}/{len(screenshot_paths)}",
            flush=True,
        )
        file_upload_ids.append(upload_png_to_notion(screenshot_path, notion_token, index))

    title = f"{run_data.condition} ({'o' if run_data.passed else 'x'})"
    toggle_block = {
        "object": "block",
        "type": "toggle",
        "toggle": {
            "rich_text": notion_rich_text_chunks(title),
            "color": "default",
            "children": notion_code_blocks(run_data.stdout)
            + [notion_image_block(file_upload_id) for file_upload_id in file_upload_ids],
        },
    }

    append_response = requests.patch(
        f"{NOTION_API_BASE_URL}/blocks/{notion_page_id}/children",
        headers=notion_headers(notion_token),
        json={"children": [toggle_block]},
        timeout=60,
    )
    ensure_notion_success(append_response, "Notion block append")
    appended_blocks = append_response.json().get("results", [])
    appended_block_id = appended_blocks[0].get("id") if appended_blocks else None

    print(
        "[notion] Appended load-test report: "
        f"{notion_page_url(notion_page_id, appended_block_id)}",
        flush=True,
    )


def main() -> int:
    args = parse_args()
    scenario = normalize_scenario_name(args.scenario)
    scenario_file = scenario_path(scenario)
    if not scenario_file.is_file():
        print(f"[error] Scenario file not found: {scenario_file}", file=sys.stderr)
        return 2

    load_dotenv(PROJECT_ROOT / ".env")

    started_at_epoch_ms = utc_epoch_ms()
    output = ""

    try:
        exit_code, output = run_k6(scenario)
        ended_at_epoch_ms = utc_epoch_ms()
        passed = exit_code == 0

        print(
            f"[result] k6 {'PASS' if passed else 'FAIL'} "
            f"(exit_code={exit_code}, start={started_at_epoch_ms}, end={ended_at_epoch_ms})",
            flush=True,
        )

        wait_for_metrics_buffer()

        run_data = K6RunResult(
            scenario=scenario,
            condition=args.condition,
            passed=passed,
            exit_code=exit_code,
            stdout=output,
            started_at_epoch_ms=started_at_epoch_ms,
            ended_at_epoch_ms=ended_at_epoch_ms,
        )
        screenshot_paths = capture_grafana_screenshots(run_data)
        upload_to_notion(run_data, screenshot_paths)
        print("[done] k6 run data captured for later report steps.", flush=True)
        return 0
    except Exception as exc:
        backup_file = save_k6_output_backup(started_at_epoch_ms, output)
        print(f"[error] {exc}", file=sys.stderr)
        print(f"[backup] Saved captured k6 output to {backup_file}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
