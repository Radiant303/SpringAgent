"""SpringAgent 应用进程管理器。

@author hanbing
@since 2026-10-18
"""

import os
import signal
import subprocess
import sys
import threading
from pathlib import Path
from urllib.request import urlopen


def healthcheck() -> int:
    engine_port = os.environ.get("SPRINGAGENT_CLOUD_PORT", "15900")
    gateway_port = os.environ.get("SERVER_PORT", "16900")
    try:
        for address in (
            f"http://127.0.0.1:{engine_port}/openapi.json",
            f"http://127.0.0.1:{gateway_port}/api/auth/public-config",
        ):
            with urlopen(address, timeout=3) as response:
                if response.status != 200:
                    return 1
    except Exception as error:
        print(f"SpringAgent 尚未就绪：{error}", file=sys.stderr, flush=True)
        return 1
    return 0


def stop(process: subprocess.Popen, timeout: int) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            process.terminate()
        else:
            os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                process.kill()
            else:
                os.killpg(process.pid, signal.SIGKILL)
            process.wait()
    except ProcessLookupError:
        pass


def serve() -> int:
    root = Path(__file__).resolve().parent
    stopping = threading.Event()
    processes: list[tuple[str, subprocess.Popen]] = []
    previous = {}
    for signum in (signal.SIGTERM, signal.SIGINT):
        previous[signum] = signal.signal(signum, lambda *_: stopping.set())
    try:
        for name, command, directory in (
            ("gateway", ["java", "-jar", str(root / "gateway" / "app.jar")], root / "gateway"),
            ("engine", [sys.executable, "-m", "spring_agent.entrypoints.cloud"], root),
        ):
            if stopping.is_set():
                return 0
            process = subprocess.Popen(command, cwd=directory, start_new_session=True)
            processes.append((name, process))
            print(f"SpringAgent {name} 已启动。", flush=True)
        while not stopping.wait(0.2):
            for name, process in processes:
                code = process.poll()
                if code is not None:
                    print(f"SpringAgent {name} 意外退出，退出码：{code}。", file=sys.stderr, flush=True)
                    return code if code > 0 else 1
        return 0
    except OSError as error:
        print(f"SpringAgent 启动失败：{error}", file=sys.stderr, flush=True)
        return 1
    finally:
        for name, process in reversed(processes):
            stop(process, 90 if name == "engine" else 15)
        for signum, handler in previous.items():
            signal.signal(signum, handler)


if __name__ == "__main__":
    sys.exit(healthcheck() if sys.argv[1:] == ["--healthcheck"] else serve())
