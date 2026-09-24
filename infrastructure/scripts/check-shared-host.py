#!/usr/bin/env python3
"""Record or compare a non-secret, read-only baseline of workloads sharing a deployment host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import urllib.parse
import urllib.request


CONTAINER_FORMAT = (
    '{"id":{{json .Id}},"image":{{json .Image}},'
    '"startedAt":{{json .State.StartedAt}},"restartCount":{{.RestartCount}},'
    '"health":{{json .State.Health.Status}}}'
)


def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=30).stdout


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("The protected workload smoke must not redirect")


def smoke(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme not in ("http", "https")
            or parsed.hostname not in ("127.0.0.1", "::1", "localhost")
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment):
        raise ValueError("Use a loopback HTTP(S) smoke URL without credentials or query data")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(url, timeout=10) as response:
        if response.status != 200:
            raise RuntimeError(f"Protected workload returned HTTP {response.status}")
    return {"url": url, "status": 200}


def capture(containers, units, config_dirs, url):
    result = {
        "schemaVersion": 2, "hostname": socket.gethostname(), "containers": {}, "units": {},
        "files": {}, "symlinks": {}, "smoke": smoke(url),
    }
    for name in containers:
        if not name or name.startswith("-"):
            raise ValueError("Invalid container name")
        state = json.loads(command("docker", "--host", "unix:///var/run/docker.sock",
                                   "inspect", "--format", CONTAINER_FORMAT, name))
        if state["health"] != "healthy":
            raise RuntimeError(f"Protected container is not healthy: {name}")
        result["containers"][name] = state
    for name in units:
        if not name.endswith(".service") or name.startswith("-"):
            raise ValueError("Use explicit .service unit names")
        output = command("systemctl", "show", name, "--property=ActiveState",
                         "--property=MainPID", "--property=ActiveEnterTimestampMonotonic")
        state = dict(line.split("=", 1) for line in output.splitlines() if line)
        if state["ActiveState"] != "active" or int(state["MainPID"]) <= 0:
            raise RuntimeError(f"Protected service is not running: {name}")
        result["units"][name] = state
    explicit_directories = {Path(directory).resolve() for directory in config_dirs}
    for directory in config_dirs:
        root = Path(directory)
        if not root.is_absolute() or not root.is_dir():
            raise ValueError("Configuration directories must be existing absolute paths")
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                result["symlinks"][str(path)] = os.readlink(path)
                if path.is_dir() and path.resolve() not in explicit_directories:
                    raise ValueError("Include linked configuration directory targets explicitly")
            if not path.is_file():
                continue
            if path.suffix in (".key", ".pem", ".crt") or path.resolve().suffix in (".key", ".pem", ".crt"):
                continue
            result["files"][str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not result["containers"] or not result["units"] or not result["files"]:
        raise ValueError("A preservation baseline requires containers, services and config files")
    return result


def compare(before, after, additions=None):
    if before.get("schemaVersion") != 2 or after.get("schemaVersion") != 2:
        raise ValueError("Record a new version-2 baseline; do not silently upgrade historical evidence")
    additions = {"files": {}, "symlinks": {}} if additions is None else additions
    if not isinstance(additions, dict) or set(additions) != {"files", "symlinks"}:
        raise ValueError("Approved additions must contain only files and symlinks")
    for field in ("hostname", "containers", "units", "smoke"):
        if before.get(field) != after.get(field):
            raise RuntimeError(f"Protected host baseline changed: {field}")
    for field in ("files", "symlinks"):
        approved = additions[field]
        if not isinstance(approved, dict):
            raise ValueError("Approved additions must map absolute paths to expected values")
        for path, value in approved.items():
            if (not isinstance(path, str) or not Path(path).is_absolute()
                    or ".." in Path(path).parts or not isinstance(value, str) or not value):
                raise ValueError("Invalid approved addition")
            if path in before["files"] or path in before["symlinks"]:
                raise ValueError(f"An addition cannot authorize changing an existing path: {path}")
        expected = {**before[field], **approved}
        if after.get(field) != expected:
            raise RuntimeError(f"Protected configuration or approved additions differ: {field}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--record", type=Path)
    mode.add_argument("--compare", type=Path)
    parser.add_argument("--container", action="append", required=True)
    parser.add_argument("--unit", action="append", required=True)
    parser.add_argument("--config-dir", action="append", required=True)
    parser.add_argument("--smoke-url", required=True)
    parser.add_argument("--additions", type=Path,
                        help="Exact new file hashes and symlink targets approved for this comparison")
    args = parser.parse_args()
    if args.record and args.additions:
        parser.error("--additions is only valid with --compare")
    os.umask(0o077)
    current = capture(args.container, args.unit, args.config_dir, args.smoke_url)
    if args.record:
        with args.record.open("x") as output:
            json.dump(current, output, indent=2)
            output.write("\n")
        print("Protected workload baseline recorded; no credentials or response bodies captured.")
    else:
        additions = json.loads(args.additions.read_text()) if args.additions else None
        compare(json.loads(args.compare.read_text()), current, additions)
        print("Protected workloads and configuration are unchanged; only exact approved additions exist.")


if __name__ == "__main__":
    main()
