#!/usr/bin/env python3
"""抓微信测试号的原始 ID（gh_…），填进 backend/.env 的 WECHAT_ORIGINAL_ID。

测试号后台不显示原始 ID —— 那是正式号「设置与开发 → 账号详情」才有的字段。
它只出现在推送 XML 的 ToUserName 里，而应用本身刻意不记录消息体
（见 WechatCallbackExceptionHandler 的注释），所以翻应用日志也抄不到。

这个脚本临时顶在回调端口上，把收到的第一条推送原样打印出来：

    ./scripts/dev-wechat-original-id.py [port]     # 默认 8080

配合 dev-wechat-tunnel.sh 用，完整步骤见 backend/README.md 的
「Local WeChat Integration」。拿到 gh_ 就 Ctrl-C 停掉，然后启动真正的
web 容器。

它不验签、不解密、不落库，任何人打到隧道地址都能让它回 success ——
只用来读一次原始 ID，绝不要留着跑，更不要用于生产。
"""
import re
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8080


class Handler(BaseHTTPRequestHandler):
    def _send(self, body: bytes, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)

        # dev-wechat-tunnel.sh 起隧道前会探这个，探不到就拒绝启动。
        if parsed.path == "/actuator/health/liveness":
            self._send(b'{"status":"UP"}')
            return

        # 微信保存「接口配置信息」时的验证请求：原样回显 echostr 即算通过。
        # 真后端会验签，这里不验 —— 只是为了让配置能存下来，好触发后续推送。
        echo = parse_qs(parsed.query).get("echostr", [""])[0]
        print(f"\n[GET] {self.path}")
        if echo:
            print("  -> 已回显 echostr，测试号后台应显示配置成功")
        self._send(echo.encode())

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "replace")
        print(f"\n[POST] {self.path}")
        print("---- 推送原文 ----")
        print(body)

        match = re.search(r"<ToUserName>.*?(gh_[A-Za-z0-9]+)", body, re.S)
        if match:
            print("\n" + "=" * 52)
            print(f"  WECHAT_ORIGINAL_ID={match.group(1)}")
            print("=" * 52)
            print("  ^ 填进 backend/.env，然后 Ctrl-C 停掉本脚本。\n")
        elif "<Encrypt>" in body:
            print("\n  收到的是密文，说明后台开了安全模式。")
            print("  改成明文模式再触发一次 —— 本脚本不解密。\n")
        else:
            print("\n  没匹配到 gh_ 开头的 ToUserName，把上面的原文贴出来看看。\n")

        self._send(b"success")

    def log_message(self, *args):
        pass  # 默认那行访问日志会盖住 XML


def main() -> None:
    # 直接开终端跑时 Python 自动行缓冲，重定向到文件时不会 —— 显式打开，
    # 免得推送来了却看不到输出。
    sys.stdout.reconfigure(line_buffering=True)
    print(f"监听 127.0.0.1:{PORT} —— 等微信推送。Ctrl-C 退出。")
    print("提示：普通聊天消息也会打印，但只有事件（关注/取关/扫码/菜单）")
    print("      才带 gh_ 之外的信息。取关再关注是最快的触发方式。\n")
    try:
        HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
    except OSError as error:
        sys.exit(
            f"错误: 无法监听 127.0.0.1:{PORT} —— {error}\n"
            f"端口可能被 web 容器占着，先 docker compose stop web。"
        )
    except KeyboardInterrupt:
        print("\n已停止。")


if __name__ == "__main__":
    main()
