#!/usr/bin/env python3
"""Small, dependency-free Replit workbench for the Android UI audit."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from svg_to_android_xml import convert_svg, make_bundle

ROOT = Path(__file__).resolve().parent.parent
STUDIO = Path(__file__).resolve().parent
TOKEN_STORE = STUDIO / ".canva_store"
INDEX = STUDIO / "index.html"
TOKEN_ENDPOINT = "https://api.canva.com/rest/v1/oauth/token"
CANVA_API = "https://api.canva.com/rest/v1"
AUTHORIZE_ENDPOINT = "https://www.canva.com/api/oauth/authorize"
COOKIE_NAME = "ui_studio_session"
SESSION_TTL = 600
BODY_LIMIT = 1_500_000
DESIGN_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,180}$")


def pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()
    ).rstrip(b"=").decode("ascii")
    return verifier, challenge


def secure_id() -> str:
    return secrets.token_urlsafe(32)


def _url(value: str) -> str:
    return value.rstrip("/")


class CanvaClient:
    """Canva OAuth and API client; credentials and tokens stay server-side."""

    def __init__(
        self,
        client_id: str,
        client_secret: str,
        redirect_uri: str,
        token_url: str = TOKEN_ENDPOINT,
        api_base: str = CANVA_API,
    ) -> None:
        self.client_id = client_id
        self.client_secret = client_secret
        self.redirect_uri = redirect_uri
        self.token_url = token_url
        self.api_base = _url(api_base)

    def _request(self, url: str, data: bytes | None = None,
                 headers: dict[str, str] | None = None) -> bytes:
        request = urllib.request.Request(url, data=data, headers=headers or {})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.read(8_000_001)
        except urllib.error.HTTPError as exc:
            # Do not surface or log upstream error bodies: they can contain request
            # details. Callers get a stable, non-sensitive error.
            if exc.code == 401:
                raise CanvaUnauthorized() from None
            raise CanvaError("Canva could not complete that request.") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise CanvaError("Canva is temporarily unreachable.") from None

    def _token_post(self, fields: dict[str, str]) -> dict[str, Any]:
        credentials = f"{self.client_id}:{self.client_secret}".encode()
        auth = base64.b64encode(credentials).decode("ascii")
        payload = urllib.parse.urlencode(fields).encode("ascii")
        body = self._request(
            self.token_url,
            data=payload,
            headers={
                "Authorization": f"Basic {auth}",
                "Content-Type": "application/x-www-form-urlencoded",
                "Accept": "application/json",
            },
        )
        try:
            result = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise CanvaError("Canva returned an unreadable token response.") from None
        if not isinstance(result, dict) or not result.get("access_token"):
            raise CanvaError("Canva did not return an access token.")
        return result

    def exchange(self, code: str, verifier: str) -> dict[str, Any]:
        return self._token_post({
            "grant_type": "authorization_code",
            "code": code,
            "code_verifier": verifier,
            "redirect_uri": self.redirect_uri,
        })

    def refresh(self, refresh_token: str) -> dict[str, Any]:
        return self._token_post({
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
        })

    def api(self, access_token: str, path: str,
            query: dict[str, str] | None = None) -> dict[str, Any]:
        url = f"{self.api_base}/{path.lstrip('/')}"
        if query:
            url += "?" + urllib.parse.urlencode(query)
        body = self._request(url, headers={
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/json",
        })
        try:
            value = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise CanvaError("Canva returned an unreadable response.") from None
        if not isinstance(value, dict):
            raise CanvaError("Canva returned an unexpected response.")
        return value

    def post_api(self, access_token: str, path: str,
                 body: dict[str, Any]) -> dict[str, Any]:
        payload = json.dumps(body).encode("utf-8")
        raw = self._request(
            f"{self.api_base}/{path.lstrip('/')}",
            data=payload,
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            value = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise CanvaError("Canva returned an unreadable response.") from None
        if not isinstance(value, dict):
            raise CanvaError("Canva returned an unexpected response.")
        return value

    def download_svg(self, url: str) -> str:
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https" or not (
            parsed.hostname and (
                parsed.hostname == "canva.com"
                or parsed.hostname.endswith(".canva.com")
            )
        ):
            raise CanvaError("Canva returned an unexpected export URL.")
        class CanvaRedirects(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request: Any, fp: Any, code: int,
                                 message: str, headers: Any, new_url: str) -> Any:
                destination = urllib.parse.urlparse(new_url)
                if destination.scheme != "https" or not (
                    destination.hostname == "canva.com"
                    or (destination.hostname or "").endswith(".canva.com")
                ):
                    raise urllib.error.URLError("Untrusted Canva redirect.")
                return super().redirect_request(
                    request, fp, code, message, headers, new_url
                )

        request = urllib.request.Request(url, headers={"Accept": "image/svg+xml"})
        try:
            opener = urllib.request.build_opener(CanvaRedirects())
            with opener.open(request, timeout=15) as response:
                payload = response.read(8_000_001)
        except (urllib.error.URLError, TimeoutError, OSError):
            raise CanvaError("Canva could not provide that SVG export.") from None
        if len(payload) > 8_000_000:
            raise CanvaError("The Canva SVG export is too large.")
        try:
            text = payload.decode("utf-8")
        except UnicodeDecodeError:
            raise CanvaError("Canva returned an invalid SVG file.") from None
        if "<svg" not in text[:4096].lower():
            raise CanvaError("Canva did not return an SVG file.")
        return text


class CanvaError(Exception):
    pass


class CanvaUnauthorized(CanvaError):
    pass


class StudioApp:
    def __init__(self, store_dir: Path = TOKEN_STORE,
                 config: dict[str, str] | None = None) -> None:
        self.store_dir = store_dir
        self.config = config or {}
        self.lock = threading.RLock()
        self.pending: dict[str, dict[str, Any]] = {}
        self.csrf: dict[str, str] = {}

    def env(self, name: str, default: str = "") -> str:
        return self.config.get(name, os.environ.get(name, default))

    @property
    def client_id(self) -> str:
        return self.env("CANVA_CLIENT_ID")

    @property
    def client_secret(self) -> str:
        return self.env("CANVA_CLIENT_SECRET")

    @property
    def scopes(self) -> list[str]:
        raw = self.env("CANVA_SCOPES", "design:meta:read design:content:read")
        return list(dict.fromkeys(s for s in re.split(r"[\s,]+", raw) if s))

    def redirect_uri(self) -> str:
        configured = self.env("CANVA_REDIRECT_URI")
        if configured:
            parsed = urllib.parse.urlparse(configured)
            if (parsed.scheme != "https" or not parsed.netloc or parsed.query or parsed.fragment
                    or parsed.path.rstrip("/") != "/auth/canva/callback"):
                raise ValueError("CANVA_REDIRECT_URI must be an HTTPS callback URL.")
            return configured
        public_base = self.env("REPLIT_APP_URL")
        if public_base:
            parsed = urllib.parse.urlparse(public_base)
            if parsed.scheme != "https" or not parsed.netloc:
                raise ValueError("REPLIT_APP_URL must be an HTTPS URL.")
            return _url(public_base) + "/auth/canva/callback"
        dev_domain = self.env("REPLIT_DEV_DOMAIN")
        if dev_domain:
            return f"https://{dev_domain.strip('/')}/auth/canva/callback"
        return ""

    def callback_is_production(self) -> bool:
        return bool(self.env("REPLIT_APP_URL"))

    def callback_state(self) -> str:
        if self.env("REPLIT_APP_URL"):
            return "production"
        if self.env("CANVA_REDIRECT_URI"):
            return "configured"
        if self.env("REPLIT_DEV_DOMAIN"):
            return "development"
        return "unavailable"

    def client(self) -> CanvaClient:
        if not (self.client_id and self.client_secret):
            raise CanvaError("Add Canva client credentials in Replit Secrets first.")
        uri = self.redirect_uri()
        if not uri:
            raise CanvaError("Set CANVA_REDIRECT_URI after publishing the studio.")
        return CanvaClient(
            self.client_id, self.client_secret, uri,
            token_url=self.config.get("CANVA_TOKEN_URL", TOKEN_ENDPOINT),
            api_base=self.config.get("CANVA_API_BASE", CANVA_API),
        )

    def sid_for(self, handler: "StudioHandler", create: bool = True) -> str | None:
        cookie = SimpleCookie()
        try:
            cookie.load(handler.headers.get("Cookie", ""))
            morsel = cookie.get(COOKIE_NAME)
            sid = morsel.value if morsel else ""
        except Exception:
            sid = ""
        if re.fullmatch(r"[A-Za-z0-9_-]{32,100}", sid or ""):
            self._ensure_csrf(sid)
            return sid
        if not create:
            return None
        sid = secure_id()
        self._ensure_csrf(sid)
        return sid

    def _ensure_csrf(self, sid: str) -> str:
        with self.lock:
            return self.csrf.setdefault(sid, secure_id())

    def token_path(self, sid: str) -> Path:
        name = hashlib.sha256(sid.encode()).hexdigest() + ".json"
        return self.store_dir / name

    def token_get(self, sid: str) -> dict[str, Any] | None:
        path = self.token_path(sid)
        try:
            data = json.loads(path.read_text("utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(data, dict) or not data.get("access_token"):
            return None
        return data

    def token_save(self, sid: str, response: dict[str, Any],
                   old: dict[str, Any] | None = None) -> dict[str, Any]:
        self.store_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        try:
            self.store_dir.chmod(0o700)
        except OSError:
            pass
        current = dict(old or {})
        current.update({
            "access_token": response["access_token"],
            "expires_at": time.time() + max(0, int(response.get("expires_in", 3600))),
            "token_type": response.get("token_type", "Bearer"),
            "scope": response.get("scope", current.get("scope", "")),
        })
        if response.get("refresh_token"):
            current["refresh_token"] = response["refresh_token"]
        path = self.token_path(sid)
        tmp = path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(current, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(tmp, path)
            path.chmod(0o600)
        except Exception:
            try:
                tmp.unlink(missing_ok=True)
            finally:
                raise
        return current

    def token_delete(self, sid: str) -> None:
        self.token_path(sid).unlink(missing_ok=True)

    def access_token(self, sid: str, force: bool = False) -> str:
        token = self.token_get(sid)
        if not token:
            raise CanvaError("Connect Canva Pro to continue.")
        refresh = force or float(token.get("expires_at", 0)) <= time.time() + 120
        if refresh:
            if not token.get("refresh_token"):
                self.token_delete(sid)
                raise CanvaError("Reconnect Canva Pro; the session has expired.")
            try:
                renewed = self.client().refresh(token["refresh_token"])
            except CanvaUnauthorized:
                self.token_delete(sid)
                raise CanvaError("Reconnect Canva Pro; the session has expired.") from None
            token = self.token_save(sid, renewed, token)
        return str(token["access_token"])

    def canva_api(self, sid: str, path: str,
                  query: dict[str, str] | None = None) -> dict[str, Any]:
        client = self.client()
        token = self.access_token(sid)
        try:
            return client.api(token, path, query)
        except CanvaUnauthorized:
            token = self.access_token(sid, force=True)
            return client.api(token, path, query)

    def canva_post(self, sid: str, path: str,
                   payload: dict[str, Any]) -> dict[str, Any]:
        client = self.client()
        token = self.access_token(sid)
        try:
            return client.post_api(token, path, payload)
        except CanvaUnauthorized:
            token = self.access_token(sid, force=True)
            return client.post_api(token, path, payload)


class StudioHandler(BaseHTTPRequestHandler):
    server_version = "UIStudio"
    sys_version = ""
    app: StudioApp

    def log_message(self, _format: str, *args: Any) -> None:
        # BaseHTTPRequestHandler logs request paths including OAuth codes/state.
        # Keep access tokens, authorization codes, and query strings out of logs.
        return

    def end_headers(self) -> None:
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob: https://canva.com https://*.canva.com; "
            "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
            "connect-src 'self'; object-src 'none'; base-uri 'none'; "
            "frame-ancestors 'none'; form-action 'self' https://www.canva.com",
        )
        super().end_headers()

    def _cookie(self, sid: str) -> None:
        forwarded_proto = self.headers.get("X-Forwarded-Proto", "")
        secure = self.app.env("COOKIE_SECURE") == "1" or forwarded_proto.split(",")[0].strip() == "https"
        cookie = f"{COOKIE_NAME}={sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000"
        if secure:
            cookie += "; Secure"
        self.send_header("Set-Cookie", cookie)

    def _send(self, status: int, body: bytes, content_type: str,
              sid: str | None = None, headers: dict[str, str] | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        if headers:
            for key, value in headers.items():
                self.send_header(key, value)
        if sid:
            self._cookie(sid)
        self.end_headers()
        self.wfile.write(body)

    def json(self, status: int, value: dict[str, Any], sid: str | None = None) -> None:
        payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self._send(status, payload, "application/json; charset=utf-8", sid)

    def error(self, status: int, message: str, sid: str | None = None) -> None:
        self.json(status, {"error": message}, sid)

    def read_json(self, max_bytes: int = BODY_LIMIT) -> dict[str, Any]:
        size = int(self.headers.get("Content-Length", "0") or "0")
        if size < 0 or size > max_bytes:
            raise ValueError("Request body is too large.")
        raw = self.rfile.read(size)
        try:
            value = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise ValueError("Send valid JSON.") from None
        if not isinstance(value, dict):
            raise ValueError("Send a JSON object.")
        return value

    def _allowed_origin(self) -> bool:
        origin = self.headers.get("Origin")
        if not origin:
            return True
        host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host", "")
        proto = self.headers.get("X-Forwarded-Proto", "http").split(",")[0].strip()
        allowed = {f"{proto}://{host.split(',')[0].strip()}".rstrip("/")}
        for base in (self.app.env("REPLIT_APP_URL"), self.app.env("CANVA_REDIRECT_URI")):
            if not base:
                continue
            parsed = urllib.parse.urlparse(base)
            if parsed.scheme and parsed.netloc:
                allowed.add(f"{parsed.scheme}://{parsed.netloc}".rstrip("/"))
        dev_domain = self.app.env("REPLIT_DEV_DOMAIN")
        if dev_domain:
            allowed.add(f"https://{dev_domain.strip('/')}")
        return any(hmac.compare_digest(origin.rstrip("/"), item) for item in allowed)

    def _require_post_session(self) -> str:
        sid = self.app.sid_for(self, create=False)
        if not sid:
            raise PermissionError("Refresh the page and try again.")
        supplied = self.headers.get("X-CSRF-Token", "")
        expected = self.app._ensure_csrf(sid)
        if not self._allowed_origin() or not hmac.compare_digest(supplied, expected):
            raise PermissionError("The request could not be verified. Refresh and try again.")
        return sid

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        params = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        sid = self.app.sid_for(self)

        if path == "/":
            try:
                data = INDEX.read_bytes()
            except OSError:
                self.error(500, "The studio page is missing.", sid)
                return
            self._send(200, data, "text/html; charset=utf-8", sid)
            return
        if path == "/favicon.ico":
            self.send_response(HTTPStatus.NO_CONTENT)
            self.send_header("Cache-Control", "public, max-age=86400")
            self.end_headers()
            return
        if path.startswith("/deliverables/"):
            self.serve_deliverable(path, sid)
            return
        if path == "/auth/canva/login":
            self.login(sid)
            return
        if path == "/auth/canva/callback":
            self.callback(sid, params)
            return
        if path == "/api/canva/status":
            token = self.app.token_get(sid)
            try:
                redirect_uri = self.app.redirect_uri()
                callback_state = self.app.callback_state()
            except ValueError:
                redirect_uri, callback_state = "", "invalid"
            self.json(200, {
                "configured": bool(self.app.client_id and self.app.client_secret),
                "connected": bool(token),
                "callbackUrl": redirect_uri,
                "callbackState": callback_state,
                "scopes": self.app.scopes,
                "csrfToken": self.app._ensure_csrf(sid),
            }, sid)
            return
        if path == "/api/canva/designs":
            try:
                sid = self.app.sid_for(self, create=False) or sid
                query = params.get("query", [""])[0][:255]
                result = self.app.canva_api(sid, "/designs", {
                    "limit": "50",
                    "sort_by": "modified_descending",
                    **({"query": query} if query else {}),
                })
                data = result.get("items", [])
                designs = []
                for item in data if isinstance(data, list) else []:
                    if isinstance(item, dict) and item.get("id"):
                        designs.append({
                            "id": str(item["id"]),
                            "title": str(item.get("title") or "Untitled design")[:180],
                            "updatedAt": item.get("updated_at"),
                            "thumbnail": item.get("thumbnail", {}).get("url")
                            if isinstance(item.get("thumbnail"), dict) else None,
                        })
                self.json(200, {"items": designs, "continuation": result.get("continuation")}, sid)
            except CanvaError as exc:
                self.error(401 if "Connect Canva" in str(exc) else 502, str(exc), sid)
            return
        match = re.fullmatch(r"/api/canva/designs/([^/]+)/formats", path)
        if match:
            design_id = urllib.parse.unquote(match.group(1))
            if not DESIGN_ID_RE.fullmatch(design_id):
                self.error(400, "Invalid design id.", sid)
                return
            try:
                result = self.app.canva_api(
                    sid, f"/designs/{urllib.parse.quote(design_id, safe='')}/export-formats"
                )
                formats = result.get("formats", {})
                self.json(200, {"formats": formats if isinstance(formats, dict) else {}}, sid)
            except CanvaError as exc:
                self.error(502, str(exc), sid)
            return
        if path == "/api/audit":
            audit = ROOT / "REPLIT_UI_ONLY_AUDIT_LOG.txt"
            try:
                self._send(200, audit.read_bytes(), "text/plain; charset=utf-8", sid)
            except OSError:
                self.error(404, "Run the UI audit first.", sid)
            return
        self.error(404, "Not found.", sid)

    def serve_deliverable(self, path: str, sid: str) -> None:
        filenames = {
            "/deliverables/audit.txt": "REPLIT_UI_ONLY_AUDIT_LOG.txt",
            "/deliverables/blueprint.md": "REPLIT_UI_REDESIGN_AND_CANVA_BLUEPRINT.md",
            "/deliverables/blueprint.pdf": "REPLIT_UI_REDESIGN_AND_CANVA_BLUEPRINT.pdf",
        }
        filename = filenames.get(path)
        if not filename:
            self.error(404, "Not found.", sid)
            return
        try:
            body = (ROOT / filename).read_bytes()
        except OSError:
            self.error(404, "That deliverable is not available yet.", sid)
            return
        content_type = "application/pdf" if filename.endswith(".pdf") else (
            "text/markdown; charset=utf-8" if filename.endswith(".md") else "text/plain; charset=utf-8"
        )
        self._send(200, body, content_type, sid, {
            "Content-Disposition": f'attachment; filename="{filename}"'
        })

    def login(self, sid: str) -> None:
        try:
            client = self.app.client()
        except (CanvaError, ValueError) as exc:
            self.error(503, str(exc), sid)
            return
        verifier, challenge = pkce_pair()
        state = secure_id()
        with self.app.lock:
            self.app.pending[sid] = {
                "state": state,
                "verifier": verifier,
                "expires": time.time() + SESSION_TTL,
            }
        query = urllib.parse.urlencode({
            "response_type": "code",
            "client_id": client.client_id,
            "redirect_uri": client.redirect_uri,
            "scope": " ".join(self.app.scopes),
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "s256",
        })
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", f"{AUTHORIZE_ENDPOINT}?{query}")
        self.send_header("Cache-Control", "no-store")
        self._cookie(sid)
        self.end_headers()

    def callback(self, sid: str, params: dict[str, list[str]]) -> None:
        supplied = params.get("state", [""])[0]
        with self.app.lock:
            pending = self.app.pending.pop(sid, None)  # one-time even on error
        if (
            not pending
            or pending["expires"] < time.time()
            or not supplied
            or not hmac.compare_digest(str(pending["state"]), supplied)
        ):
            self.error(400, "Canva authorization could not be verified. Start again.", sid)
            return
        if params.get("error"):
            self.redirect_home(sid, "cancelled")
            return
        code = params.get("code", [""])[0]
        if not code or len(code) > 4096:
            self.error(400, "Canva did not return an authorization code.", sid)
            return
        try:
            response = self.app.client().exchange(code, pending["verifier"])
            self.app.token_save(sid, response)
        except (CanvaError, ValueError):
            self.redirect_home(sid, "failed")
            return
        self.redirect_home(sid, "connected")

    def redirect_home(self, sid: str, result: str) -> None:
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", f"/?canva={urllib.parse.quote(result)}")
        self.send_header("Cache-Control", "no-store")
        self._cookie(sid)
        self.end_headers()

    def do_POST(self) -> None:
        path = urllib.parse.urlparse(self.path).path
        try:
            sid = self._require_post_session()
        except PermissionError as exc:
            self.error(403, str(exc))
            return

        if path in ("/api/canva/refresh", "/auth/canva/refresh"):
            try:
                self.app.access_token(sid, force=True)
                self.json(200, {"ok": True}, sid)
            except CanvaError as exc:
                self.error(401, str(exc), sid)
            return
        if path in ("/api/canva/disconnect", "/auth/canva/disconnect"):
            self.app.token_delete(sid)
            self.json(200, {"ok": True, "connected": False}, sid)
            return
        if path == "/api/canva/test":
            try:
                self.app.canva_api(sid, "/designs", {"limit": "1"})
                self.json(200, {"ok": True, "message": "Canva connection is working."}, sid)
            except CanvaError as exc:
                self.error(502, str(exc), sid)
            return
        if path == "/api/canva/export-svg":
            self.export_canva_svg(sid)
            return
        if path == "/api/svg/convert":
            self.convert_upload(sid, bundle=False)
            return
        if path == "/api/svg/bundle":
            self.convert_upload(sid, bundle=True)
            return
        if path == "/api/audit/rerun":
            try:
                proc = subprocess.run(
                    [sys.executable, str(STUDIO / "audit.py")],
                    cwd=ROOT, capture_output=True, text=True, timeout=30, check=False,
                )
            except (OSError, subprocess.TimeoutExpired):
                self.error(500, "The audit could not be rerun.", sid)
                return
            if proc.returncode:
                self.error(500, "The audit could not be completed.", sid)
                return
            self.json(200, {"ok": True, "message": "UI audit refreshed."}, sid)
            return
        self.error(404, "Not found.", sid)

    def export_canva_svg(self, sid: str) -> None:
        try:
            data = self.read_json(32_000)
        except ValueError as exc:
            self.error(400, str(exc), sid)
            return
        design_id = str(data.get("design_id", ""))
        if not DESIGN_ID_RE.fullmatch(design_id):
            self.error(400, "Choose a valid Canva design.", sid)
            return
        try:
            formats = self.app.canva_api(
                sid, f"/designs/{urllib.parse.quote(design_id, safe='')}/export-formats"
            ).get("formats", {})
            if not isinstance(formats, dict) or not formats.get("svg"):
                self.error(422, "Canva does not list SVG export for this design. Download an SVG in Canva and upload it here.", sid)
                return
            job = self.app.canva_post(sid, "/exports", {
                "design_id": design_id,
                "format": {"type": "svg"},
            })
            job_data = job.get("job", job)
            job_id = str(job_data.get("id", ""))
            if not DESIGN_ID_RE.fullmatch(job_id):
                self.error(502, "Canva did not create the SVG export.", sid)
                return
            deadline = time.monotonic() + 25
            result = job_data
            while time.monotonic() < deadline:
                status = result.get("status")
                if status == "success":
                    break
                if status in ("failed", "cancelled"):
                    self.error(422, "Canva could not export this design as SVG. Upload an SVG file instead.", sid)
                    return
                time.sleep(0.6)
                result = self.app.canva_api(
                    sid, f"/exports/{urllib.parse.quote(job_id, safe='')}"
                ).get("job", {})
            else:
                self.error(504, "The Canva export is still processing. Try again shortly.", sid)
                return
            urls = result.get("urls", [])
            if not isinstance(urls, list) or not urls:
                self.error(502, "Canva did not provide an SVG download.", sid)
                return
            svg = self.app.client().download_svg(str(urls[0]))
            converted = convert_svg(svg)
            self.json(200, {"svg": svg, "conversion": converted.public_result()}, sid)
        except CanvaError as exc:
            self.error(502, str(exc), sid)
        except ValueError as exc:
            self.error(422, str(exc), sid)

    def convert_upload(self, sid: str, bundle: bool) -> None:
        try:
            data = self.read_json()
            svg = data.get("svg")
            if not isinstance(svg, str):
                raise ValueError("Upload an SVG file first.")
            if len(svg.encode("utf-8")) > 1_000_000:
                raise ValueError("SVG files must be smaller than 1 MB.")
            name = str(data.get("name") or "ic_custom").strip()
            kind = str(data.get("kind") or "icon")
            result = convert_svg(svg, icon_name=name, kind=kind)
            if bundle:
                payload = make_bundle(result, name, kind)
                self._send(200, payload, "application/zip", sid, {
                    "Content-Disposition": f'attachment; filename="{result.file_stem}-android-vector.zip"'
                })
            else:
                self.json(200, result.public_result(), sid)
        except ValueError as exc:
            self.error(422, str(exc), sid)


def build_handler(app: StudioApp) -> type[StudioHandler]:
    return type("BoundStudioHandler", (StudioHandler,), {"app": app})


def main() -> None:
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "5000"))
    app = StudioApp()
    httpd = ThreadingHTTPServer((host, port), build_handler(app))
    httpd.daemon_threads = True
    print(f"UI Studio listening on {host}:{port}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
