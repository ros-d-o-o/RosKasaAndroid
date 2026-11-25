import json
import subprocess
import sys
import time
from pathlib import Path

ADB_EXE = r"C:\Users\mm684\AppData\Local\Android\Sdk\platform-tools\adb.exe"
MCP_EXE = r"C:\GeminiCli\Delphi\Autopilot-for-Delphi-main\Source\McpServer\Autopilot.Mcp.exe"


def ensure_adb_forward(package: str = "si.ros.RosKasaLight", port: int = 18011) -> int:
    """Ensure app is running on Android and adb forward tcp:<port> -> @Autopilot.<pid> is active."""
    res = subprocess.run([ADB_EXE, "shell", "pidof", package], capture_output=True, text=True)
    pid_out = res.stdout.strip()
    if not pid_out:
        subprocess.run([ADB_EXE, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False)
        subprocess.run([ADB_EXE, "shell", "am", "start", "-n", f"{package}/com.embarcadero.firemonkey.FMXNativeActivity"], check=False)
        for _ in range(15):
            time.sleep(0.5)
            res = subprocess.run([ADB_EXE, "shell", "pidof", package], capture_output=True, text=True)
            pid_out = res.stdout.strip()
            if pid_out:
                break
    if not pid_out:
        raise RuntimeError(f"Package {package} is not running on Android device.")
    pid = int(pid_out.split()[0])
    subprocess.run([ADB_EXE, "forward", f"tcp:{port}", f"localabstract:Autopilot.{pid}"], check=True)
    return pid


class AutopilotClient:
    """Persistent JSON-RPC client talking to Autopilot.Mcp.exe (Android adb:<port> or Win32 auto-attach)."""

    def __init__(self, port: int | None = 18011):
        self.port = port
        self._id = 0
        cmd = [MCP_EXE] if port is None else [MCP_EXE, "--target", f"adb:{port}"]
        self.proc = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        self._rpc("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "roskasa-regression", "version": "1.0"},
        })
        self._notify("notifications/initialized", {})

    def _notify(self, method: str, params: dict):
        msg = {"jsonrpc": "2.0", "method": method, "params": params}
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()

    def _rpc(self, method: str, params: dict) -> dict:
        self._id += 1
        req_id = self._id
        msg = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        while True:
            line = self.proc.stdout.readline()
            if not line:
                err = self.proc.stderr.read()
                raise RuntimeError(f"Autopilot.Mcp exited unexpectedly. stderr={err}")
            line = line.strip()
            if not line:
                continue
            resp = json.loads(line)
            if resp.get("id") == req_id:
                return resp

    def call(self, tool_name: str, **arguments) -> dict:
        resp = self._rpc("tools/call", {"name": tool_name, "arguments": arguments})
        if "error" in resp:
            raise RuntimeError(f"MCP error in {tool_name}: {resp['error']}")
        result = resp.get("result", {})
        if result.get("isError"):
            raise RuntimeError(f"Tool error in {tool_name}: {result}")
        content = result.get("content", [])
        if content and content[0].get("type") == "text":
            inner = json.loads(content[0]["text"])
            if inner.get("ok") is False or "error" in inner:
                raise RuntimeError(f"Bridge error in {tool_name}: {inner}")
            return inner.get("result", inner)
        return result

    def close(self):
        try:
            if self.proc.stdin:
                self.proc.stdin.close()
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


if __name__ == "__main__":
    ensure_adb_forward()
    forms = sys.argv[1:] if len(sys.argv) > 1 else ["frmNFClogin", "frmKasaMobile"]
    with AutopilotClient() as ap:
        ap.call("set_keep_awake", enabled=True)
        for form in forms:
            res = ap.call("list_tree", form=form)
            comps = res.get("components", [])
            print(f"=== {form} ({len(comps)} comps) ===")
            for c in comps:
                cls = c.get("class", "")
                name = c.get("name", "")
                if (
                    cls in ("TfrmNFClogin", "TfrmKasaMobile", "TButton", "TSpeedButton", "TEdit", "TTabControl", "TTabItem", "TListView", "TComboBox", "TSwitch", "TCheckBox", "TNumberBox")
                    or any(k in name.lower() for k in ("btn", "edt", "tab", "lv", "miza", "plac", "izpis", "naroc", "storno", "popust", "pin"))
                ):
                    if c.get("visible", True) or cls == "TTabItem":
                        print(f"  {c['path']} [{cls}] text={c.get('text', '')!r} vis={c.get('visible')}")
