from __future__ import annotations

import base64
import contextlib
import io
import hashlib
import json
import os
import secrets
import tempfile
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from server import StudioApp, build_handler, pkce_pair, secure_id
from svg_to_android_xml import convert_svg, make_bundle


SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
    '<path d="M3 12h18M12 3v18" fill="none" stroke="#000" stroke-width="2"/>'
    '</svg>'
)


class MockCanvaHandler(BaseHTTPRequestHandler):
    events: list[tuple[str, str, dict[str, str]]] = []
    next_access = "unused"
    next_refresh = "unused"
    valid_access: set[str] = set()

    def log_message(self, *_args):
        return

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        form = dict(urllib.parse.parse_qsl(raw.decode()))
        authorization = self.headers.get("Authorization", "")
        self.events.append((self.path, authorization, form))
        if form.get("grant_type") == "authorization_code":
            access = self.next_access
            self.valid_access.add(access)
            result = {
                "access_token": access,
                "refresh_token": self.next_refresh,
                "expires_in": 3600,
                "token_type": "Bearer",
            }
        elif form.get("grant_type") == "refresh_token":
            access = self.next_access
            self.valid_access.add(access)
            result = {
                "access_token": access,
                "refresh_token": self.next_refresh,
                "expires_in": 3600,
                "token_type": "Bearer",
            }
        else:
            self.send_error(400)
            return
        body = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        auth = self.headers.get("Authorization", "")
        token = auth.removeprefix("Bearer ")
        if self.path.startswith("/rest/v1/designs"):
            if token not in self.valid_access:
                self.send_response(401)
                self.end_headers()
                return
            body = json.dumps({"items": []}).encode()
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class StudioTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        MockCanvaHandler.events = []
        MockCanvaHandler.valid_access = set()
        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), MockCanvaHandler)
        cls.upstream.daemon_threads = True
        cls.upstream_thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.upstream_thread.start()
        cls.upstream_base = f"http://127.0.0.1:{cls.upstream.server_port}/rest/v1"

    @classmethod
    def tearDownClass(cls):
        cls.upstream.shutdown()
        cls.upstream.server_close()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.client_id = secrets.token_urlsafe(24)
        self.client_secret = secrets.token_urlsafe(32)
        MockCanvaHandler.events = []
        MockCanvaHandler.next_access = "access-" + secure_id()
        MockCanvaHandler.next_refresh = "refresh-" + secure_id()
        config = {
            "CANVA_CLIENT_ID": self.client_id,
            "CANVA_CLIENT_SECRET": self.client_secret,
            "CANVA_REDIRECT_URI": "https://studio.example.test/auth/canva/callback",
            "CANVA_TOKEN_URL": self.upstream_base + "/oauth/token",
            "CANVA_API_BASE": self.upstream_base,
        }
        self.app = StudioApp(Path(self.tmp.name), config)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), build_handler(self.app))
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"
        self.cookie = ""

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmp.cleanup()

    def request(self, path: str, method: str = "GET", body: dict | None = None,
                headers: dict[str, str] | None = None, allow_redirects: bool = False):
        hdr = dict(headers or {})
        if self.cookie:
            hdr["Cookie"] = self.cookie
        if body is not None:
            hdr["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        else:
            data = None
        req = urllib.request.Request(self.base + path, data=data, headers=hdr, method=method)
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, _request, _fp, _code, _message, _headers, _url):
                return None

        opener = urllib.request.build_opener() if allow_redirects else urllib.request.build_opener(NoRedirect())
        try:
            response = opener.open(req, timeout=5)
        except urllib.error.HTTPError as exc:
            response = exc
        cookie = response.headers.get("Set-Cookie")
        if cookie:
            self.cookie = cookie.split(";", 1)[0]
        return response.status, response.headers, response.read()

    def csrf(self):
        status, _headers, raw = self.request("/api/canva/status")
        self.assertEqual(status, 200)
        return json.loads(raw)["csrfToken"]

    def post_headers(self):
        return {"X-CSRF-Token": self.csrf(), "Origin": self.base}

    def login(self):
        status, headers, _body = self.request("/auth/canva/login")
        self.assertEqual(status, 302)
        params = urllib.parse.parse_qs(urllib.parse.urlparse(headers["Location"]).query)
        return params

    def test_pkce_s256_and_verifier_constraints(self):
        verifier, challenge = pkce_pair()
        self.assertGreaterEqual(len(verifier), 43)
        self.assertLessEqual(len(verifier), 128)
        self.assertRegex(verifier, r"^[A-Za-z0-9._~-]+$")
        expected = base64.urlsafe_b64encode(
            hashlib.sha256(verifier.encode()).digest()
        ).rstrip(b"=").decode()
        self.assertEqual(challenge, expected)

    def test_state_mismatch_and_replay_are_rejected(self):
        first = self.login()
        status, _headers, _body = self.request(
            "/auth/canva/callback?state=wrong&code=ignored"
        )
        self.assertEqual(status, 400)
        status, _headers, _body = self.request(
            "/auth/canva/callback?" + urllib.parse.urlencode({
                "state": first["state"][0], "code": "replay"
            })
        )
        self.assertEqual(status, 400)
        self.assertEqual(MockCanvaHandler.events, [])

    def test_expired_state_is_rejected(self):
        self.request("/api/canva/status")
        sid = self.cookie.split("=", 1)[1]
        state = secure_id()
        self.app.pending[sid] = {
            "state": state,
            "verifier": secure_id(),
            "expires": time.time() - 1,
        }
        status, _headers, _body = self.request(
            "/auth/canva/callback?" + urllib.parse.urlencode({
                "state": state, "code": "expired-" + secure_id()
            })
        )
        self.assertEqual(status, 400)
        self.assertEqual(MockCanvaHandler.events, [])

    def test_callback_exchanges_code_once_and_stores_tokens_privately(self):
        params = self.login()
        code = "authorization-" + secure_id()
        status, headers, _body = self.request(
            "/auth/canva/callback?" + urllib.parse.urlencode({
                "state": params["state"][0], "code": code
            })
        )
        self.assertEqual(status, 302)
        self.assertIn("canva=connected", headers["Location"])
        self.assertEqual(len(MockCanvaHandler.events), 1)
        path, authorization, form = MockCanvaHandler.events[0]
        self.assertEqual(path, "/rest/v1/oauth/token")
        decoded = base64.b64decode(authorization.removeprefix("Basic ")).decode()
        self.assertEqual(decoded, self.client_id + ":" + self.client_secret)
        self.assertEqual(form["grant_type"], "authorization_code")
        self.assertEqual(form["code"], code)
        self.assertGreaterEqual(len(form["code_verifier"]), 43)
        saved = next(Path(self.tmp.name).glob("*.json"))
        self.assertEqual(saved.stat().st_mode & 0o777, 0o600)
        status, _headers, _body = self.request(
            "/auth/canva/callback?" + urllib.parse.urlencode({
                "state": params["state"][0], "code": code
            })
        )
        self.assertEqual(status, 400)

    def test_refresh_and_api_401_retry(self):
        self.request("/api/canva/status")
        sid = self.cookie.split("=", 1)[1]
        self.app._ensure_csrf(sid)
        self.app.token_save(sid, {
            "access_token": "expired-" + secure_id(),
            "refresh_token": "refresh-" + secure_id(),
            "expires_in": 3600,
        })
        MockCanvaHandler.next_access = "refreshed-" + secure_id()
        result = self.app.canva_api(sid, "/designs", {"limit": "1"})
        self.assertEqual(result["items"], [])
        self.assertTrue(any(event[2].get("grant_type") == "refresh_token" for event in MockCanvaHandler.events))
        saved = self.app.token_get(sid)
        self.assertEqual(saved["access_token"], MockCanvaHandler.next_access)

    def test_refresh_and_connection_test_routes(self):
        self.request("/api/canva/status")
        sid = self.cookie.split("=", 1)[1]
        self.app.token_save(sid, {
            "access_token": "before-" + secure_id(),
            "refresh_token": "refresh-" + secure_id(),
            "expires_in": 3600,
        })
        MockCanvaHandler.next_access = "route-refreshed-" + secure_id()
        headers = self.post_headers()
        status, _response_headers, body = self.request(
            "/auth/canva/refresh", method="POST", headers=headers
        )
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])
        status, _response_headers, body = self.request(
            "/api/canva/test", method="POST", headers=headers
        )
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])
        status, _response_headers, body = self.request(
            "/auth/canva/disconnect", method="POST", headers=headers
        )
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(body)["connected"])

    def test_status_html_and_logs_never_expose_credentials(self):
        status, _headers, page = self.request("/")
        self.assertEqual(status, 200)
        self.assertNotIn(self.client_id.encode(), page)
        self.assertNotIn(self.client_secret.encode(), page)
        status, _headers, body = self.request("/api/canva/status")
        self.assertEqual(status, 200)
        self.assertNotIn(self.client_id.encode(), body)
        self.assertNotIn(self.client_secret.encode(), body)
        self.assertNotIn(b"access_token", body)
        self.assertNotIn(b"refresh_token", body)
        status, _headers, _body = self.request(
            "/api/canva/disconnect", method="POST", headers={"X-CSRF-Token": "bad"}
        )
        self.assertEqual(status, 403)
        sensitive_code = "private-code-" + secure_id()
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
            self.request("/auth/canva/callback?state=untrusted&code=" + sensitive_code)
        self.assertNotIn(sensitive_code, captured.getvalue())
        self.assertNotIn(self.client_secret, captured.getvalue())

    def test_svg_conversion_and_android_bundle(self):
        result = convert_svg(SVG, "cinema_mark", "icon")
        parsed = __import__("xml.etree.ElementTree", fromlist=["ElementTree"])
        root = parsed.fromstring(result.xml)
        self.assertEqual(root.tag.rsplit("}", 1)[-1], "vector")
        self.assertIn("android:pathData", result.xml)
        self.assertIn("<path", result.preview_svg)
        bundle = make_bundle(convert_svg(SVG, "launcher_mark", "adaptive"), "launcher_mark", "adaptive")
        import io
        import zipfile
        with zipfile.ZipFile(io.BytesIO(bundle)) as archive:
            names = set(archive.namelist())
            self.assertIn("res/mipmap-anydpi-v26/ic_launcher.xml", names)
            self.assertIn("res/drawable/ic_launcher_mark_monochrome.xml", names)
            for name in names:
                if name.endswith(".xml"):
                    parsed.fromstring(archive.read(name))

    def test_svg_http_convert_and_bundle_routes(self):
        headers = self.post_headers()
        payload = {"svg": SVG, "name": "cinema_mark", "kind": "adaptive"}
        status, _response_headers, body = self.request(
            "/api/svg/convert", method="POST", body=payload, headers=headers
        )
        self.assertEqual(status, 200)
        result = json.loads(body)
        self.assertIn("android:pathData", result["xml"])
        self.assertIn("<path", result["previewSvg"])
        status, response_headers, archive = self.request(
            "/api/svg/bundle", method="POST", body=payload, headers=headers
        )
        self.assertEqual(status, 200)
        self.assertEqual(response_headers.get_content_type(), "application/zip")
        import zipfile
        with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
            self.assertIn("res/mipmap-anydpi-v26/ic_launcher.xml", bundle.namelist())

    def test_svg_inherited_styles_and_rejects_lossy_values(self):
        svg = (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
            '<g fill="red" stroke="#111111" stroke-width="2" stroke-linecap="round" '
            'transform="translate(1,2)"><path d="M1 1h5"/></g></svg>'
        )
        result = convert_svg(svg)
        root = __import__("xml.etree.ElementTree", fromlist=["ElementTree"]).fromstring(result.xml)
        android = "{http://schemas.android.com/apk/res/android}"
        group = root.find("group")
        self.assertIsNotNone(group)
        path = group.find("path")
        self.assertEqual(path.attrib[android + "fillColor"], "#FFFFFFFF")
        self.assertEqual(path.attrib[android + "strokeLineCap"], "round")
        self.assertEqual(path.attrib[android + "strokeWidth"], "2")
        for svg in (
            SVG.replace('stroke="#000"', 'stroke="#00000080"'),
            SVG.replace("viewBox=", 'onload="alert(1)" viewBox='),
            SVG.replace(
                '<path ', '<g transform="translate(2oops)"><path ', 1
            ).replace("</svg>", "</g></svg>"),
        ):
            with self.assertRaises(ValueError):
                convert_svg(svg)

    def test_svg_rejects_scripts_styles_and_external_entities(self):
        for svg in (
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><script>alert(1)</script></svg>',
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path style="fill:red" d="M0 0"/></svg>',
            '<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///etc/passwd">]><svg>&x;</svg>',
        ):
            with self.assertRaises(ValueError):
                convert_svg(svg)


if __name__ == "__main__":
    unittest.main()
