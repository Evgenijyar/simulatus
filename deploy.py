#!/usr/bin/env python3
"""Deploy the current Simulatus backend from GitHub with Docker.

Run inside the cloned repository on the server:
    python3 deploy.py

The script performs git fetch/reset, Docker build, container replacement,
health checking, and automatic rollback. Secrets stay outside Git in an env file.
"""
from __future__ import annotations
import json, os, pathlib, stat, subprocess, sys, time, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
BRANCH = os.getenv("SIMULATUS_BRANCH", "main")
CONTAINER = os.getenv("SIMULATUS_CONTAINER_NAME", "simulatus-backend")
PREVIOUS = CONTAINER + "-previous"
IMAGE = os.getenv("SIMULATUS_IMAGE_NAME", "simulatus-backend:latest")
HTTP_BIND = os.getenv("SIMULATUS_HTTP_BIND", "127.0.0.1")
# 8080=b24-video-offer, 8081=OKK, 8082=Prodamus on the current application server.
HTTP_PORT = os.getenv("SIMULATUS_HTTP_PORT", "8083")
ENV_FILE = pathlib.Path(os.getenv("SIMULATUS_ENV_FILE", "/etc/simulatus/backend.env"))
MEMORY_LIMIT = os.getenv("SIMULATUS_MEMORY_LIMIT", "512m")
MEMORY_SWAP_LIMIT = os.getenv("SIMULATUS_MEMORY_SWAP_LIMIT", "768m")
LOG_VOLUME = os.getenv("SIMULATUS_LOG_VOLUME", "simulatus-logs")
HEALTH_URL = f"http://127.0.0.1:{HTTP_PORT}/actuator/health"
REQUIRED_ENV = {
    "SIMULATUS_DB_HOST",
    "SIMULATUS_DB_PORT",
    "SIMULATUS_DB_NAME",
    "SIMULATUS_DB_USER",
    "SIMULATUS_DB_PASSWORD",
    "SIMULATUS_MASTER_KEY",
    "SIMULATUS_ADMIN_LOGIN",
    "SIMULATUS_ADMIN_PASSWORD",
    "SIMULATUS_SESSION_SECURE",
}

def run(*args: str, check: bool=True, quiet: bool=False):
    print("+", " ".join(args), flush=True)
    return subprocess.run(args,cwd=ROOT,text=True,check=check,stdout=subprocess.DEVNULL if quiet else None,stderr=subprocess.DEVNULL if quiet else None)

def exists_container(name: str) -> bool:
    return subprocess.run(["docker","container","inspect",name],cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode == 0

def env_keys(path: pathlib.Path) -> set[str]:
    keys=set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line=raw.strip()
        if line and not line.startswith("#") and "=" in line: keys.add(line.split("=",1)[0].strip())
    return keys

def validate_environment() -> bool:
    if not ENV_FILE.is_file(): print(f"ERROR: env file not found: {ENV_FILE}",file=sys.stderr); return False
    missing=sorted(REQUIRED_ENV-env_keys(ENV_FILE))
    if missing: print("ERROR: env file is missing: "+", ".join(missing),file=sys.stderr); return False
    permissions=stat.S_IMODE(ENV_FILE.stat().st_mode)
    if permissions & 0o077: print(f"ERROR: {ENV_FILE} permissions are {permissions:04o}; use chmod 600.",file=sys.stderr); return False
    return True

def wait_until_healthy(timeout_seconds: int=100) -> bool:
    deadline=time.monotonic()+timeout_seconds; last=""
    while time.monotonic()<deadline:
        try:
            with urllib.request.urlopen(HEALTH_URL,timeout=3) as response:
                payload=json.loads(response.read().decode("utf-8"))
                if response.status==200 and payload.get("status")=="UP": return True
                last=f"HTTP {response.status}: {payload}"
        except (urllib.error.URLError,TimeoutError,json.JSONDecodeError,OSError) as exc: last=str(exc)
        time.sleep(2)
    print(f"Health check failed: {last}",file=sys.stderr); return False

def start_container():
    run("docker","run","-d","--name",CONTAINER,"--restart","unless-stopped","--env-file",str(ENV_FILE),"--memory",MEMORY_LIMIT,"--memory-swap",MEMORY_SWAP_LIMIT,"--log-opt","max-size=20m","--log-opt","max-file=3","-p",f"{HTTP_BIND}:{HTTP_PORT}:8080","-v",f"{LOG_VOLUME}:/app/logs",IMAGE)

def main() -> int:
    if not (ROOT/".git").exists(): print("ERROR: run deploy.py from a cloned Git repository.",file=sys.stderr); return 2
    if not validate_environment(): return 2
    run("git","fetch","origin",BRANCH); run("git","checkout",BRANCH); run("git","reset","--hard",f"origin/{BRANCH}")
    run("docker","build","--pull","-t",IMAGE,".")
    if exists_container(PREVIOUS): run("docker","rm","-f",PREVIOUS)
    had_previous=exists_container(CONTAINER)
    if had_previous: run("docker","stop",CONTAINER); run("docker","rename",CONTAINER,PREVIOUS)
    try:
        start_container(); print(f"Waiting for {HEALTH_URL} ...",flush=True)
        if not wait_until_healthy(): raise RuntimeError("new container did not become healthy")
    except Exception as exc:
        print(f"\nDEPLOY FAILED: {exc}",file=sys.stderr)
        if exists_container(CONTAINER): run("docker","rm","-f",CONTAINER,check=False)
        if had_previous and exists_container(PREVIOUS): run("docker","rename",PREVIOUS,CONTAINER,check=False); run("docker","start",CONTAINER,check=False); print("Previous container restored.",file=sys.stderr)
        return 1
    if had_previous and exists_container(PREVIOUS): run("docker","rm",PREVIOUS)
    print("\nContainer is healthy. Recent logs:\n"); run("docker","logs","--tail","80",CONTAINER,check=False)
    print(f"\nDone. {CONTAINER} -> {HTTP_BIND}:{HTTP_PORT}"); return 0

if __name__ == "__main__": raise SystemExit(main())
