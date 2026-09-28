#!/usr/bin/env python3
"""Exercise the real Nginx config in disposable Docker containers.

Run: python3 deploy/tests/nginx-dns-regression.py
Requires Python 3, Docker with Compose, nginx:alpine and python:3.12-slim.
Only test containers/network with this invocation's unique prefix are removed.
No application image, production network, database or credentials are used.
For a legacy-config negative control, set TEST_NGINX_CONFIG to that config and
TEST_BACKEND_BEFORE_NGINX=1 (skip only the formerly unsupported startup case).
"""

import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


DEPLOY = Path(__file__).resolve().parents[1]
NGINX_IMAGE = os.environ.get("TEST_NGINX_IMAGE", "nginx:alpine")
BACKEND_IMAGE = os.environ.get("TEST_BACKEND_IMAGE", "python:3.12-slim")
PREFIX = "nginx-dns-test-" + uuid.uuid4().hex[:12]
NETWORK = PREFIX + "-net"
WEB = PREFIX + "-web"
OLD = PREFIX + "-old"
NEW = PREFIX + "-new"
BLOCKER = PREFIX + "-occupy-old-ip"
CONTAINERS = []
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

BACKEND = r'''
import json, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path == "/api/test/redirect":
            self.send_response(302)
            self.send_header("Location", "/auth/login?next=%2Fworkbench")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        code = 401 if self.path == "/api/auth/me" else 200
        if self.path.startswith("/api/v1/health"):
            result = {"code": 200, "message": "ok", "data": os.environ["HEALTH_DATA"]}
        elif code == 401:
            result = {"code": 401, "message": "unauthenticated", "data": None}
        else:
            result = {"instance": os.environ["INSTANCE"], "method": self.command,
                      "path": self.path, "body": body.decode("utf-8"),
                      "headers": dict(self.headers)}
        output = json.dumps(result, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(output)))
        self.end_headers()
        self.wfile.write(output)

    do_GET = respond
    do_POST = respond

ThreadingHTTPServer(("0.0.0.0", int(os.environ["PORT"])) , Handler).serve_forever()
'''


def docker(*args, check=True):
    result = subprocess.run(["docker", *args], text=True, capture_output=True, timeout=45)
    if check and result.returncode:
        raise RuntimeError(f"docker {' '.join(args[:4])}: {result.stderr.strip()}")
    return result


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def inspect(name):
    return json.loads(docker("inspect", name).stdout)[0]


def container(name, *args):
    # Register before creation so partial starts are cleaned up as well.
    CONTAINERS.append(name)
    docker("run", "-d", "--name", name, "--label", f"regression-run={PREFIX}",
           "--network", NETWORK, *args)


def healthchecks():
    checks = {}
    production = DEPLOY / "docker-compose.prod.yml"
    for path in sorted(DEPLOY.glob("docker-compose.*.yml")):
        compose_files = ["-f", str(production)]
        if path != production:
            compose_files += ["-f", str(path)]
        result = docker("compose", *compose_files, "config", "--format", "json",
                        "--no-env-resolution", "--no-interpolate", "--no-normalize")
        services = json.loads(result.stdout)["services"]
        if web_service in services:
            test = services[web_service].get("healthcheck", {}).get("test")
            require(test and test[0] == "CMD-SHELL", f"{path.name}: missing web CMD-SHELL healthcheck")
            checks[path.name] = test[1]
    require(checks, "No web Compose healthcheck found")
    return checks


def request(path, *, method="GET", data=None, headers=None):
    req = urllib.request.Request(base_url + path, data=data, method=method,
                                 headers=headers or {})
    try:
        with HTTP.open(req, timeout=4) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def wait_for(predicate, timeout=10):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            if predicate():
                return
        except (OSError, ValueError) as error:
            last = error
        time.sleep(0.2)
    raise AssertionError(f"Condition not met within {timeout}s; last error: {last}")


def backend(name, instance, directory):
    container(name, "--network-alias", backend_service,
              "-e", f"PORT={backend_port}", "-e", f"INSTANCE={instance}",
              "-e", f"HEALTH_DATA={health_data}",
              "-v", f"{directory}/backend.py:/test/backend.py:ro",
              BACKEND_IMAGE, "python", "/test/backend.py")


def interrupted(signum, frame):
    raise KeyboardInterrupt(f"signal {signum}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


signal.signal(signal.SIGTERM, interrupted)

if __name__ == "__main__":
    config = Path(os.environ.get("TEST_NGINX_CONFIG", str(DEPLOY / "frontend-nginx.conf"))).resolve()
    match = re.search(r"http://((?:vend|worland)-server):(\d+)", config.read_text())
    require(match, "Cannot identify backend service in Nginx config")
    backend_service, backend_port = match.groups()
    health_data = ("vending-erp" if backend_service == "vend-server" else "worland-rent") + " backend alive"
    web_service = backend_service.replace("-server", "-web")
    checks = healthchecks()
    try:
        with tempfile.TemporaryDirectory(prefix=PREFIX) as directory:
            Path(directory, "backend.py").write_text(BACKEND)
            # A single worker makes DNS-cache reuse deterministic: a fresh worker
            # must not accidentally hide a stale-cache failure during recreation.
            Path(directory, "nginx.conf").write_text(
                "worker_processes 1;\nerror_log /dev/stderr notice;\n"
                "events { worker_connections 128; }\n"
                "http { include /etc/nginx/mime.types; access_log /dev/stdout; "
                "include /etc/nginx/conf.d/*.conf; }\n")
            docker("network", "create", "--label", f"regression-run={PREFIX}", NETWORK)
            backend_first = os.environ.get("TEST_BACKEND_BEFORE_NGINX") == "1"
            if backend_first:
                backend(OLD, "old", directory)
            container(WEB, "-p", "127.0.0.1::80", "-v",
                      f"{config}:/etc/nginx/conf.d/default.conf:ro", "-v",
                      f"{directory}/nginx.conf:/etc/nginx/nginx.conf:ro", NGINX_IMAGE)
            web_before = inspect(WEB)
            host_port = web_before["NetworkSettings"]["Ports"]["80/tcp"][0]["HostPort"]
            base_url = f"http://127.0.0.1:{host_port}"
            wait_for(lambda: request("/")[0] == 200)
            docker("exec", WEB, "nginx", "-t")
            if not backend_first:
                require(request("/api/v1/health")[0] >= 500,
                        "API unexpectedly available without any backend")
                backend(OLD, "old", directory)
            wait_for(lambda: request("/api/v1/health")[0] == 200)
            if not backend_first:
                print("PASS Nginx starts without backend DNS and recovers when backend appears", flush=True)

            path = "/api/echo/a%2Fb?tag=a%2Bb&dup=first&dup=second&blank="
            headers = {"Host": "proxy-regression.invalid", "Authorization": "Bearer regression-only",
                       "X-Forwarded-For": "192.0.2.10", "X-Regression-Test": PREFIX}
            status, body = request(path, headers=headers)
            require(status == 200, f"GET failed: HTTP {status}, {body[:300]!r}")
            result = json.loads(body)
            received = {key.lower(): value for key, value in result["headers"].items()}
            require(status == 200 and result["path"] == path and result["method"] == "GET",
                    "GET path/query was changed")
            require(received.get("host") == headers["Host"], "Host was not forwarded")
            require(received.get("authorization") == headers["Authorization"], "Authorization was not forwarded")
            require(received.get("x-regression-test") == PREFIX, "Custom header was not forwarded")
            require(received.get("x-real-ip"), "X-Real-IP missing")
            require(received.get("x-forwarded-for", "").startswith("192.0.2.10, "), "Client IP chain missing")
            require(received.get("x-forwarded-proto") == "http", "Protocol header missing")
            payload = json.dumps({"text": "租赁 regression + /", "number": 42}, ensure_ascii=False).encode()
            status, body = request(path, method="POST", data=payload,
                                   headers={**headers, "Content-Type": "application/json"})
            result = json.loads(body)
            require(status == 200 and result["method"] == "POST" and result["path"] == path
                    and result["body"].encode() == payload, "POST body/method/path was changed")
            require(result["headers"].get("Content-Type") == "application/json", "Content-Type changed")
            boundary = "regression-boundary"
            multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                         "filename=\"sample.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                         f"upload test 中文\r\n--{boundary}--\r\n").encode()
            content_type = f"multipart/form-data; boundary={boundary}"
            status, body = request("/api/test/upload", method="POST", data=multipart,
                                   headers={"Content-Type": content_type})
            result = json.loads(body)
            require(status == 200 and result["body"].encode() == multipart
                    and result["headers"].get("Content-Type") == content_type,
                    "Multipart upload body or boundary changed")
            no_redirect = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            try:
                no_redirect.open(base_url + "/api/test/redirect", timeout=4)
                raise AssertionError("Expected an unchanged HTTP 302 redirect")
            except urllib.error.HTTPError as error:
                require(error.code == 302 and error.headers.get("Location") == "/auth/login?next=%2Fworkbench",
                        "SSO-style relative redirect changed")
            status, body = request("/api/auth/me")
            require(status == 401 and json.loads(body)["code"] == 401, "Anonymous 401 was hidden")
            for name, command in checks.items():
                require(docker("exec", WEB, "sh", "-c", command, check=False).returncode == 0,
                        f"{name}: healthcheck failed with a healthy API")
            print("PASS GET path/query, POST and multipart bodies, proxy headers, 302, anonymous 401, healthy API checks", flush=True)

            old_ip = inspect(OLD)["NetworkSettings"]["Networks"][NETWORK]["IPAddress"]
            docker("rm", "-f", OLD)
            # Occupy the exact old address so recreation cannot accidentally reuse it.
            container(BLOCKER, "--ip", old_ip, NGINX_IMAGE)
            require(request("/")[0] == 200, "Static homepage unexpectedly failed")
            require(request("/api/v1/health")[0] >= 500, "API outage was not observable")
            for name, command in checks.items():
                require(docker("exec", WEB, "sh", "-c", command, check=False).returncode != 0,
                        f"{name}: healthcheck missed API outage while homepage stayed 200")
            print("PASS homepage remains 200 while every Compose web healthcheck detects API outage", flush=True)

            backend(NEW, "new", directory)
            new_ip = inspect(NEW)["NetworkSettings"]["Networks"][NETWORK]["IPAddress"]
            require(new_ip != old_ip, "Backend recreation did not change IP")
            started = time.monotonic()
            wait_for(lambda: (lambda reply: reply[0] == 200 and json.loads(reply[1]).get("instance") == "new")(
                request("/api/echo/recovered?query=preserved")))
            elapsed = time.monotonic() - started
            web_after = inspect(WEB)
            require(web_after["State"]["StartedAt"] == web_before["State"]["StartedAt"]
                    and web_after["RestartCount"] == web_before["RestartCount"], "Nginx container restarted")
            for name, command in checks.items():
                require(docker("exec", WEB, "sh", "-c", command, check=False).returncode == 0,
                        f"{name}: healthcheck did not recover")
            print(f"PASS backend IP {old_ip} -> {new_ip}; recovered in {elapsed:.2f}s without Nginx reload/restart", flush=True)
    except BaseException:
        print(docker("logs", "--tail", "15", WEB, check=False).stderr, flush=True)
        raise
    finally:
        for name in reversed(CONTAINERS):
            docker("rm", "-f", name, check=False)
        docker("network", "rm", NETWORK, check=False)
