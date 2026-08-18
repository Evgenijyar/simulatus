#!/usr/bin/env python3
"""One-command production deploy for Simulatus Backend.

Run from /opt/simulatus:
    python3 deploy.py

The deploy is build-first / replace-second, checks /actuator/health and
automatically restores the previous container if the new version fails.
"""
from __future__ import annotations

import json
import os
import pathlib
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
BRANCH = os.getenv("SIMULATUS_BRANCH", "main")
CONTAINER = os.getenv("SIMULATUS_CONTAINER_NAME", "simulatus-backend")
PREVIOUS = f"{CONTAINER}-previous"
IMAGE = os.getenv("SIMULATUS_IMAGE_NAME", "simulatus-backend:latest")
HTTP_BIND = os.getenv("SIMULATUS_HTTP_BIND", "127.0.0.1")
HTTP_PORT = os.getenv("SIMULATUS_HTTP_PORT", "8083")
ENV_FILE = pathlib.Path(os.getenv("SIMULATUS_ENV_FILE", "/etc/simulatus/backend.env"))
MEMORY_LIMIT = os.getenv("SIMULATUS_MEMORY_LIMIT", "512m")
MEMORY_SWAP_LIMIT = os.getenv("SIMULATUS_MEMORY_SWAP_LIMIT", "768m")
LOG_VOLUME = os.getenv("SIMULATUS_LOG_VOLUME", "simulatus-logs")
HEALTH_URL = f"http://127.0.0.1:{HTTP_PORT}/actuator/health"
REQUIRED_ENV = {
    "SIMULATUS_DB_HOST", "SIMULATUS_DB_PORT", "SIMULATUS_DB_NAME",
    "SIMULATUS_DB_USER", "SIMULATUS_DB_PASSWORD", "SIMULATUS_MASTER_KEY",
    "SIMULATUS_ADMIN_LOGIN", "SIMULATUS_ADMIN_PASSWORD", "SIMULATUS_SESSION_SECURE",
}

def run(*args: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(args), flush=True)
    return subprocess.run(
        args, cwd=ROOT, text=True, check=check,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )

def container_exists(name: str) -> bool:
    return subprocess.run(
        ["docker", "container", "inspect", name], cwd=ROOT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0

def env_keys(path: pathlib.Path) -> set[str]:
    keys: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            keys.add(line.split("=", 1)[0].strip())
    return keys

def validate() -> None:
    if not (ROOT / ".git").is_dir():
        raise RuntimeError(f"{ROOT} is not a Git checkout")
    if not ENV_FILE.is_file():
        raise RuntimeError(f"env file not found: {ENV_FILE}")
    missing = sorted(REQUIRED_ENV - env_keys(ENV_FILE))
    if missing:
        raise RuntimeError("env file is missing: " + ", ".join(missing))
    mode = stat.S_IMODE(ENV_FILE.stat().st_mode)
    if mode & 0o077:
        raise RuntimeError(f"{ENV_FILE} must be chmod 600 (current {mode:04o})")

def wait_healthy(timeout: int = 120) -> bool:
    deadline = time.monotonic() + timeout
    last = "no response"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(HEALTH_URL, timeout=3) as response:
                payload = json.loads(response.read().decode("utf-8"))
                if response.status == 200 and payload.get("status") == "UP":
                    return True
                last = f"HTTP {response.status}: {payload}"
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
            last = str(exc)
        time.sleep(2)
    print(f"Health check failed: {last}", file=sys.stderr)
    return False

def start_new_container() -> None:
    run(
        "docker", "run", "-d", "--name", CONTAINER,
        "--restart", "unless-stopped",
        "--stop-timeout", "30",
        "--env-file", str(ENV_FILE),
        "--memory", MEMORY_LIMIT, "--memory-swap", MEMORY_SWAP_LIMIT,
        "--security-opt", "no-new-privileges:true",
        "--cap-drop", "ALL",
        "--log-opt", "max-size=20m", "--log-opt", "max-file=3",
        "-p", f"{HTTP_BIND}:{HTTP_PORT}:8080",
        "-v", f"{LOG_VOLUME}:/app/logs",
        IMAGE,
    )

def main() -> int:
    try:
        validate()
        run("git", "fetch", "--prune", "origin", BRANCH)
        run("git", "checkout", "-f", BRANCH)
        run("git", "reset", "--hard", f"origin/{BRANCH}")
        commit = run("git", "rev-parse", "--short=12", "HEAD", capture=True).stdout.strip()
        print(f"Deploying commit {commit}", flush=True)

        # Build while the old container is still serving traffic.
        run("docker", "build", "--pull", "--label", f"org.opencontainers.image.revision={commit}", "-t", IMAGE, ".")

        if container_exists(PREVIOUS):
            run("docker", "rm", "-f", PREVIOUS)
        had_previous = container_exists(CONTAINER)
        if had_previous:
            run("docker", "stop", CONTAINER)
            run("docker", "rename", CONTAINER, PREVIOUS)

        try:
            start_new_container()
            print(f"Waiting for {HEALTH_URL} ...", flush=True)
            if not wait_healthy():
                raise RuntimeError("new container did not become healthy")
        except Exception:
            if container_exists(CONTAINER):
                run("docker", "rm", "-f", CONTAINER, check=False)
            if had_previous and container_exists(PREVIOUS):
                run("docker", "rename", PREVIOUS, CONTAINER, check=False)
                run("docker", "start", CONTAINER, check=False)
                print("Previous container restored.", file=sys.stderr)
            raise

        if had_previous and container_exists(PREVIOUS):
            run("docker", "rm", PREVIOUS)
        print("\nSimulatus Backend is healthy. Recent logs:\n")
        run("docker", "logs", "--tail", "80", CONTAINER, check=False)
        print(f"\nDONE: {CONTAINER} -> {HTTP_BIND}:{HTTP_PORT} (commit {commit})")
        return 0
    except Exception as exc:
        print(f"\nDEPLOY FAILED: {exc}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
