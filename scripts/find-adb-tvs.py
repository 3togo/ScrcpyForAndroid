#!/usr/bin/env python3
"""Find all ADB-accessible devices, including TV boxes with tablet firmware (Python 3 + adb + ip)."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import ipaddress
import json
import os
import shutil
import socket
import subprocess
import sys
import time


def run(*args, timeout=8):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return result.returncode, result.stdout.rstrip("\r\n"), result.stderr.strip()
    except subprocess.TimeoutExpired:
        return 124, "", "command timed out"


def local_networks():
    if not shutil.which("ip"):
        raise ValueError("Automatic discovery needs Linux iproute2; supply --cidr instead.")
    code, output, error = run("ip", "-j", "-4", "route", "show", "default")
    if code:
        raise ValueError(error)
    devices = {r["dev"] for r in json.loads(output) if "dev" in r}
    code, output, error = run("ip", "-j", "-4", "addr", "show", "up")
    if code:
        raise ValueError(error)
    return sorted({str(ipaddress.ip_network(f"{a['local']}/{a['prefixlen']}", strict=False))
                   for interface in json.loads(output) if interface["ifname"] in devices
                   for a in interface.get("addr_info", []) if a.get("scope") == "global"})


def devices(adb):
    code, output, _ = run(adb, "devices")
    return {parts[0]: parts[1] for line in output.splitlines()
            if len(parts := line.split()) >= 2 and parts[1] in
            {"device", "offline", "unauthorized", "no"}} if code == 0 else {}


def mdns_endpoints(output):
    # Pairing services are NOT ADB connection ports.
    result = set()
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 3 and parts[-2].rstrip(".") in {
            "_adb._tcp", "_adb-tls-connect._tcp"
        }:
            result.add(parts[-1])
    return result


def identify(adb, serial):
    code, output, error = run(adb, "-s", serial, "shell",
        "getprop ro.product.manufacturer; getprop ro.product.model; "
        "getprop ro.build.version.release; getprop ro.build.characteristics; pm list features")
    if code:
        return "CONNECTED", serial, "Details unavailable: " + (error or output), "unknown"
    lines = output.splitlines()
    if len(lines) < 4:
        return "CONNECTED", serial, "Device information incomplete", "unknown"
    maker, model, version, characteristics = [line.strip() for line in lines[:4]]
    features = {line.strip() for line in lines[4:]}
    tv_hint = ("tv" in characteristics.split(",") or
               bool(features & {"feature:android.software.leanback", "feature:android.hardware.type.television"}))
    details = (f"{maker} {model}".strip() or "Unknown model")
    details += f"; Android {version or 'unknown'}; firmware={characteristics or 'unspecified'}"
    # Firmware hints are not a verdict on the physical device type.
    return "CONNECTED", serial, details, "present" if tv_hint else "not reported"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cidr", action="append", help="IPv4 network; repeat for multiple networks")
    parser.add_argument("--ports", default="5555", help="Legacy ADB ports, comma-separated (default: 5555)")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"), help="ADB executable or path")
    parser.add_argument("--timeout", type=float, default=.4, help="TCP probe timeout in seconds")
    parser.add_argument("--label", action="append", default=[], metavar="SERIAL=NAME",
                        help="User-provided device label; repeat as needed (not saved)")
    args = parser.parse_args()
    labels = {}
    for item in args.label:
        serial, separator, name = item.partition("=")
        if not separator or not serial.strip() or not name.strip():
            parser.error("Labels must use SERIAL=NAME with both parts nonempty.")
        labels[serial.strip()] = name.strip()
    adb = shutil.which(args.adb)
    if not adb:
        parser.error("adb not found. Install Android platform-tools or pass --adb /path/to/adb")
    try:
        networks = [ipaddress.IPv4Network(n, strict=False) for n in (args.cidr or local_networks())]
        ports = sorted({int(p) for p in args.ports.split(",")})
        if not networks:
            raise ValueError("No default-route IPv4 network found; supply --cidr.")
        if not ports or any(not 1 <= p <= 65535 for p in ports):
            raise ValueError("Ports must be between 1 and 65535.")
        if not 0 < args.timeout <= 30:
            raise ValueError("Timeout must be between 0 and 30 seconds.")
        if sum(n.num_addresses for n in networks) * len(ports) > 8192:
            raise ValueError("Scan too large; choose smaller --cidr networks / fewer ports (max 8192 probes).")
    except ValueError as error:
        parser.error(str(error))

    code, _, error = run(adb, "start-server", timeout=15)
    if code:
        parser.error(f"Cannot start ADB: {error}")
    existing = devices(adb)
    # Give the ADB server time to receive multicast service advertisements.
    run(adb, "mdns", "services")
    time.sleep(2)
    code, output, _ = run(adb, "mdns", "services")
    candidates = mdns_endpoints(output) if code == 0 else set()
    if code:
        print("mDNS unavailable; using port scan and existing ADB devices.", file=sys.stderr)
    print(f"Scanning {', '.join(map(str, networks))}, ports {ports}; also checking mDNS and existing ADB devices…",
          file=sys.stderr)

    def probe(target):
        host, port = target
        try:
            with socket.create_connection((host, port), timeout=args.timeout):
                return f"{host}:{port}"
        except OSError:
            return None

    targets = {(str(host), port) for network in networks for host in network.hosts() for port in ports}
    with ThreadPoolExecutor(max_workers=48) as pool:
        candidates.update(endpoint for endpoint in pool.map(probe, sorted(targets)) if endpoint)

    def connect(endpoint):
        _, output, error = run(adb, "connect", endpoint, timeout=5)
        return endpoint, output or error

    with ThreadPoolExecutor(max_workers=8) as pool:
        attempts = dict(pool.map(connect, sorted(candidates - existing.keys())))
    connected = devices(adb)
    results = []
    for serial, state in sorted(connected.items()):
        if state == "device":
            results.append(identify(adb, serial))
        else:
            results.append((state.upper(), serial, "Authorize/pair or reconnect", "unknown"))
    for serial, message in attempts.items():
        if serial not in connected:
            results.append(("UNVERIFIED", serial, message, "unknown"))
    print("ADB STATUS\tADB ADDRESS / SERIAL\tDEVICE / DETAILS\tTV FLAGS\tUSER LABEL")
    for status, serial, detail, hint in sorted(results):
        label = labels.get(serial, "—")
        print("\t".join(" ".join(value.split()) for value in (status, serial, detail, hint, label)))
    count = sum(status == "CONNECTED" for status, _, _, _ in results)
    print(f"\n{count} ADB-accessible device(s), including any TV boxes running tablet/phone firmware.")
    print("TV flags are firmware hints only: missing flags do not exclude a TV box.")
    print("Physical device type is not automatically confirmed. User labels are supplied by you.")
    print("Discovery covers the selected ports, mDNS advertisements, and existing ADB connections.")
    print("Discovered ADB connections are left available for use; no pairing or device settings are changed.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
