#!/usr/bin/env python3
"""仅用于 Debug-Local 的本地 HTTP/H5 与认证兑换替身，不连接生产接口。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
import struct
import zlib
from urllib.parse import urlparse, parse_qs

RECORDS = []
LOCK = threading.Lock()
ROOT = Path(__file__).resolve().parent


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, value, status=200, content_type="application/json; charset=utf-8"):
        data = value if isinstance(value, bytes) else (json.dumps(value, ensure_ascii=False).encode() if content_type.startswith("application/json") else value.encode())
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self.send({"fixture": "qsm-ios", "version": 1})
        if url.path == "/__requests":
            with LOCK:
                return self.send(list(RECORDS))
        if url.path in ["/images/default.png", "/images/active.png"]:
            with LOCK:
                RECORDS.append({"path": url.path, "type": "image"})
            color = (35, 160, 85) if url.path.endswith("default.png") else (220, 40, 60)
            raw = b"".join(b"\0" + b"".join(bytes((*color, 255 if (x-23.5)**2+(y-23.5)**2 < 23**2 else 0)) for x in range(48)) for y in range(48))
            def chunk(kind, data):
                return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
            png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 48, 48, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
            return self.send(png, content_type="image/png")
        if url.path == "/evil":
            return self.send("<h1>不可信页面</h1>", content_type="text/html; charset=utf-8")
        if url.path.startswith("/pages/"):
            name = url.path.rsplit("/", 1)[-1]
            if name not in ["agreement", "login", "game", "welfare"]:
                return self.send({}, 404)
            text = (ROOT / "page.html").read_text().replace("__PAGE__", name)
            floating = parse_qs(url.query).get("scenario", [""])[0].startswith("floatball")
            if floating:
                text = text.replace("</style>", "body{font-size:13px;padding:8px 18px}h1{font-size:18px;margin:4px 0}p{margin:5px 0}button,a{padding:8px 10px;margin:3px 5px 3px 0}</style>")
            controls = '<button onclick="call(\'setFloatingBall\',{show:true,active:true})">显示激活悬浮球</button><button onclick="call(\'setFloatingBall\',{show:false,active:true})">隐藏悬浮球</button><button onclick="call(\'setFloatingBall\',{show:true,active:false})">恢复默认悬浮球</button>' if floating else ""
            welfare = '<button onclick="call(\'setFloatingBall\',{show:false,active:true})">福利中隐藏激活球</button><button onclick="call(\'changeGameUrl\',{url:gameURL()})">换地址返回游戏</button>' if floating else ""
            text = text.replace("__FLOATING_CONTROLS__", controls).replace("__WELFARE_CONTROLS__", welfare)
            return self.send(text, content_type="text/html; charset=utf-8")
        return self.send({}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 262144:
            return self.send({}, 413)
        body = self.rfile.read(length)
        with LOCK:
            RECORDS.append({"path": self.path, "headers": {key: self.headers.get(key) for key in
                ["Gameid", "Gameversion", "Packagecode", "Devicetype", "Devicecode", "Accountoken"]},
                "body": json.loads(body or b"{}")})
        parts = urlparse(self.path).path.split("/")
        scenario = parts[2] if len(parts) > 3 and parts[1] == "scenario" else "normal"
        if self.path.endswith("h5_app/init"):
            if scenario == "init-error":
                return self.send({"code": 100, "msg": "本地初始化失败"})
            if scenario == "invalid-code":
                return self.send({"data": {}})
            base = f"http://localhost:{self.server.server_port}"
            pages = {name: {"instance": "web_" + name, "url": f"{base}/pages/{name}", "width": "100%", "height": "100%", "left": 0, "top": 0}
                     for name in ["agreement", "login", "game", "welfare"]}
            auth = {"open": 0, "config": "关闭状态下故意提供无效子配置"}
            if scenario == "onekey":
                auth = {"open": 1, "config": {"scheme_secret": "local-test-secret", "exchange_url": base + "/exchange", "launch_total_timeout_ms": 1000}}
            data = {"privacyAuto": scenario != "no-privacy", "h5Pages": pages,
                "jumpDomain": [], "aliyunOnekeyConfig": auth, "updateInfo": {"open": 0}}
            if scenario.startswith("floatball"):
                for page in pages.values():
                    page["url"] += "?scenario=" + scenario
                data["floatball"] = {"show": scenario != "floatball-hidden", "image_default": base + "/images/default.png",
                    "image_active": base + "/images/active.png", "width": 36, "height": 36, "right": 10, "top": 0.2}
                if scenario == "floatball-heartbeat":
                    data["popupInterval"] = {"url": base + "/scenario/floatball-heartbeat/poll", "interval": 2}
            return self.send({"code": 0, "msg": "ok", "data": data})
        if self.path == "/scenario/floatball-heartbeat/poll":
            with LOCK:
                count = sum(row["path"] == self.path for row in RECORDS)
            return self.send({"code": 0, "data": {"floating_ball": {"show": count != 1, "active": True}}})
        if self.path.endswith("h5_app/login_by_token"):
            if scenario == "delayed-login":
                time.sleep(2)
            if scenario == "token-rejected":
                return self.send({"code": 401, "msg": "测试 Token 已失效"})
            return self.send({"code": 0, "data": {"token": "fixture-renewed-token"}})
        if self.path == "/exchange":
            return self.send({"code": 0, "data": {"token": "fixture-exchanged-token"}})
        return self.send({}, 404)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18740)
    parser.add_argument("--ready-file", type=Path)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    if args.ready_file:
        args.ready_file.write_text(str(server.server_port))
    print(f"本地测试服务：http://localhost:{server.server_port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
