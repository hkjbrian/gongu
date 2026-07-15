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
from urllib.parse import urlencode

import requests
from dotenv import load_dotenv
from playwright.sync_api import Page, sync_playwright


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
REPORTS_DIR = PROJECT_ROOT / "load-test" / "reports"
PROMETHEUS_SCRAPE_BUFFER_SECONDS = 30
GRAFANA_VIEWPORT = {"width": 1920, "height": 3200}
GRAFANA_DEVICE_SCALE_FACTOR = 2
GRAFANA_SCREENSHOT_MARGIN_PX = 8
GRAFANA_PANEL_SETTLE_MS = 3000
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
NOTION_API_BASE_URL = "https://api.notion.com/v1"
NOTION_VERSION = "2026-03-11"
NOTION_RICH_TEXT_CONTENT_LIMIT = 2000
NOTION_RICH_TEXT_ITEMS_PER_CODE_BLOCK = 100

GRAFANA_CAPTURE_GROUPS: tuple[dict, ...] = (
    {
        "name": "spring_boot_basic_statistics",
        "dashboard_uid": "spring_boot_21",
        "label": "Spring Boot - Basic Statistics",
        "panel_ids": [52, 58, 60, 66, 56, 95, 96],
    },
    {
        "name": "spring_boot_jvm_gc",
        "dashboard_uid": "spring_boot_21",
        "label": "Spring Boot - JVM Statistics (GC)",
        "panel_ids": [74, 76],
    },
    {
        "name": "spring_boot_hikaricp",
        "dashboard_uid": "spring_boot_21",
        "label": "Spring Boot - HikariCP Connection Pool",
        "panel_ids": [44, 36, 46, 38, 42, 40],
    },
    {
        "name": "jvm_misc",
        "dashboard_uid": "efoj0uvwhzq4gf",
        "label": "JVM (Micrometer) - Misc",
        "panel_ids": [106, 93, 32, 124, 138, 91, 61],
    },
    {
        "name": "mysql_top_overview",
        "dashboard_uid": "549c2bf8936f7767ea6ac47c47b00f2a",
        "label": "MySQL - 상단 개요",
        "panel_ids": [397, 395, 396],
    },
    {
        "name": "mysql_key_metrics",
        "dashboard_uid": "549c2bf8936f7767ea6ac47c47b00f2a",
        "label": "MySQL - 주요 지표",
        "panel_ids": [92, 10, 48, 32],
    },
    {
        "name": "order_section_timing",
        "dashboard_uid": "gongu-service-overview",
        "label": "Order 생성 구간별 소요시간",
        "panel_ids": [19, 20, 21, 22, 23, 24, 25],
    },
)

GRAFANA_DASHBOARD_SLUGS = {
    "gongu-service-overview": "gongu-service-overview",
    "549c2bf8936f7767ea6ac47c47b00f2a": "mysql-exporter-quickstart-and-dashboard",
    "efoj0uvwhzq4gf": "jvm-micrometer",
    "spring_boot_21": "spring-boot-3-x-statistics",
}

GRAFANA_DASHBOARD_VARIABLES = {
    "efoj0uvwhzq4gf": {
        "application": "gongu-server",
        "instance": "host.docker.internal:8080",
    },
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


@dataclass(frozen=True)
class GrafanaScreenshot:
    label: str
    path: Path


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
        "--quiet",
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


def grafana_dashboard_url(
    grafana_url: str,
    dashboard_uid: str,
    from_ms: int,
    to_ms: int,
) -> str:
    dashboard_slug = GRAFANA_DASHBOARD_SLUGS[dashboard_uid]
    query_params: list[tuple[str, object]] = [
        ("from", from_ms),
        ("to", to_ms),
        ("orgId", 1),
    ]
    query_params.extend(
        (f"var-{name}", value)
        for name, value in GRAFANA_DASHBOARD_VARIABLES.get(dashboard_uid, {}).items()
    )
    return f"{grafana_url}/d/{dashboard_uid}/{dashboard_slug}?{urlencode(query_params)}"


def ensure_grafana_session(
    page: Page,
    grafana_url: str,
    grafana_user: str,
    grafana_password: str,
) -> None:
    response = page.context.request.post(
        f"{grafana_url}/login",
        data={"user": grafana_user, "password": grafana_password},
    )
    if response.status != 200:
        body_preview = response.text()[:300].replace("\n", " ")
        raise RuntimeError(
            f"Grafana login failed: HTTP {response.status}: {body_preview}"
        )


def wait_for_grafana_panels(page: Page, panel_ids: list[int]) -> None:
    for panel_id in panel_ids:
        page.locator(f"[data-panelid='{panel_id}']").wait_for(
            state="visible",
            timeout=60000,
        )

    page.wait_for_load_state("networkidle", timeout=60000)
    page.wait_for_function(
        """() => document.querySelectorAll('[aria-label="Loading"], [data-testid="data-testid Loading indicator"], [data-testid="loading-indicator"]').length === 0""",
        timeout=60000,
    )
    page.wait_for_timeout(GRAFANA_PANEL_SETTLE_MS)


def panel_group_clip(page: Page, panel_ids: list[int]) -> dict[str, float]:
    panel_boxes = []
    for panel_id in panel_ids:
        panel = page.locator(f"[data-panelid='{panel_id}']")
        if panel.count() != 1:
            raise RuntimeError(
                f"Expected exactly one Grafana panel for panel_id={panel_id}, "
                f"found {panel.count()}"
            )

        box = panel.bounding_box()
        if box is None:
            raise RuntimeError(f"Grafana panel has no bounding box: panel_id={panel_id}")
        panel_boxes.append(box)

    min_x = max(min(box["x"] for box in panel_boxes) - GRAFANA_SCREENSHOT_MARGIN_PX, 0)
    min_y = max(min(box["y"] for box in panel_boxes) - GRAFANA_SCREENSHOT_MARGIN_PX, 0)
    max_x = min(
        max(box["x"] + box["width"] for box in panel_boxes) + GRAFANA_SCREENSHOT_MARGIN_PX,
        GRAFANA_VIEWPORT["width"],
    )
    max_y = max(box["y"] + box["height"] for box in panel_boxes) + GRAFANA_SCREENSHOT_MARGIN_PX

    return {
        "x": min_x,
        "y": min_y,
        "width": max_x - min_x,
        "height": max_y - min_y,
    }


def capture_grafana_screenshots(run_data: K6RunResult) -> list[GrafanaScreenshot]:
    grafana_url = os.environ.get("GRAFANA_URL", "http://localhost:3001").rstrip("/")
    grafana_user = os.environ.get("GRAFANA_USER", "admin")
    grafana_password = os.environ.get("GRAFANA_PASSWORD", "admin")
    from_ms = run_data.started_at_epoch_ms
    to_ms = run_data.ended_at_epoch_ms + (PROMETHEUS_SCRAPE_BUFFER_SECONDS * 1000)

    output_dir = REPORTS_DIR / utc_iso_from_epoch_ms(run_data.started_at_epoch_ms)
    output_dir.mkdir(parents=True, exist_ok=True)

    screenshots: list[GrafanaScreenshot] = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        try:
            context = browser.new_context(
                http_credentials={
                    "username": grafana_user,
                    "password": grafana_password,
                },
                viewport=GRAFANA_VIEWPORT,
                device_scale_factor=GRAFANA_DEVICE_SCALE_FACTOR,
                locale="en-US",
            )
            page = context.new_page()
            ensure_grafana_session(page, grafana_url, grafana_user, grafana_password)

            for index, group in enumerate(GRAFANA_CAPTURE_GROUPS, start=1):
                group_name = group["name"]
                group_label = group["label"]
                dashboard_uid = group["dashboard_uid"]
                panel_ids = group["panel_ids"]
                dashboard_url = grafana_dashboard_url(
                    grafana_url,
                    dashboard_uid,
                    from_ms,
                    to_ms,
                )

                print(f"[capture] Capturing row-{index}: {group_name}", flush=True)
                page.goto(dashboard_url, wait_until="networkidle", timeout=60000)
                wait_for_grafana_panels(page, panel_ids)

                screenshot_path = output_dir / f"row-{index}-{group_name}.png"
                page.screenshot(path=screenshot_path, clip=panel_group_clip(page, panel_ids))
                screenshots.append(GrafanaScreenshot(label=group_label, path=screenshot_path))
        finally:
            browser.close()

    return screenshots


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


def notion_caption_block(label: str) -> dict[str, object]:
    return {
        "object": "block",
        "type": "paragraph",
        "paragraph": {
            "rich_text": [
                {
                    "type": "text",
                    "text": {"content": label},
                    "annotations": {"bold": True},
                }
            ],
            "color": "default",
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


def upload_to_notion(run_data: K6RunResult, screenshots: list[GrafanaScreenshot]) -> None:
    notion_token = os.environ.get("NOTION_TOKEN")
    notion_page_id = os.environ.get("NOTION_PAGE_ID")
    if not notion_token:
        raise RuntimeError("NOTION_TOKEN is not set")
    if not notion_page_id:
        raise RuntimeError("NOTION_PAGE_ID is not set")

    file_upload_ids: list[str] = []
    for index, screenshot in enumerate(screenshots, start=1):
        print(
            f"[notion] Uploading screenshot {index}/{len(screenshots)}",
            flush=True,
        )
        file_upload_ids.append(upload_png_to_notion(screenshot.path, notion_token, index))

    image_children: list[dict[str, object]] = []
    for screenshot, file_upload_id in zip(screenshots, file_upload_ids, strict=True):
        image_children.append(notion_caption_block(screenshot.label))
        image_children.append(notion_image_block(file_upload_id))

    title = f"{run_data.condition} ({'o' if run_data.passed else 'x'})"
    toggle_block = {
        "object": "block",
        "type": "toggle",
        "toggle": {
            "rich_text": notion_rich_text_chunks(title),
            "color": "default",
            "children": notion_code_blocks(run_data.stdout) + image_children,
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
        screenshots = capture_grafana_screenshots(run_data)
        upload_to_notion(run_data, screenshots)
        print("[done] k6 run data captured for later report steps.", flush=True)
        return 0
    except Exception as exc:
        backup_file = save_k6_output_backup(started_at_epoch_ms, output)
        print(f"[error] {exc}", file=sys.stderr)
        print(f"[backup] Saved captured k6 output to {backup_file}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
