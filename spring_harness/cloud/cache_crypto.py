"""配置缓存的主密钥与认证加密。"""

from __future__ import annotations

import base64
import ctypes
import json
import os
import stat
import struct
import tempfile
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MASTER_KEY_ENV = "SPRINGHARNESS_CACHE_MASTER_KEY"
_PREFIX = "enc:v1:"
_AAD_DOMAIN = "springharness-cache:v1"


def _base64_decode(value: str) -> bytes:
    decoded = base64.b64decode(value, validate=True)
    if base64.b64encode(decoded).decode("ascii") != value:
        raise ValueError("非规范 Base64")
    return decoded


def _decode_key(value: str) -> bytes:
    key = _base64_decode(value)
    if len(key) != 32:
        raise ValueError("主密钥长度无效")
    return key


def _reject_link(path: Path, *, directory: bool = False) -> os.stat_result:
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
        raise ValueError("主密钥路径不允许链接")
    if directory:
        if not stat.S_ISDIR(info.st_mode):
            raise ValueError("主密钥目录无效")
    elif not stat.S_ISREG(info.st_mode):
        raise ValueError("主密钥文件无效")
    return info


class _WindowsPrivacy:
    """当前服务账号专用的 Windows 文件权限。

    @author hanbing
    @since 2026-10-17
    """

    def __init__(self) -> None:
        from ctypes import wintypes

        self._kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self._advapi = ctypes.WinDLL("advapi32", use_last_error=True)
        self._kernel.GetCurrentProcess.restype = wintypes.HANDLE
        self._kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self._kernel.LocalFree.argtypes = [wintypes.HLOCAL]
        self._advapi.OpenProcessToken.argtypes = [
            wintypes.HANDLE, wintypes.DWORD, ctypes.POINTER(wintypes.HANDLE),
        ]
        self._advapi.GetTokenInformation.argtypes = [
            wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD,
            ctypes.POINTER(wintypes.DWORD),
        ]
        self._advapi.ConvertSidToStringSidW.argtypes = [
            wintypes.LPVOID, ctypes.POINTER(wintypes.LPWSTR),
        ]
        self._advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [
            wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(wintypes.LPVOID),
            ctypes.POINTER(wintypes.DWORD),
        ]
        self._advapi.GetSecurityDescriptorDacl.argtypes = [
            wintypes.LPVOID, ctypes.POINTER(wintypes.BOOL),
            ctypes.POINTER(wintypes.LPVOID), ctypes.POINTER(wintypes.BOOL),
        ]
        self._advapi.SetNamedSecurityInfoW.argtypes = [
            wintypes.LPWSTR, ctypes.c_int, wintypes.DWORD, wintypes.LPVOID,
            wintypes.LPVOID, wintypes.LPVOID, wintypes.LPVOID,
        ]
        self._advapi.SetNamedSecurityInfoW.restype = wintypes.DWORD
        token = wintypes.HANDLE()
        sid_text = wintypes.LPWSTR()
        if not self._advapi.OpenProcessToken(
            self._kernel.GetCurrentProcess(), 0x0008, ctypes.byref(token),
        ):
            raise OSError("无法获取服务账号")
        try:
            size = wintypes.DWORD()
            self._advapi.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))
            buffer = ctypes.create_string_buffer(size.value)
            if not self._advapi.GetTokenInformation(
                token, 1, buffer, size, ctypes.byref(size),
            ):
                raise OSError("无法获取服务账号")
            sid = ctypes.cast(buffer, ctypes.POINTER(wintypes.LPVOID))[0]
            if not self._advapi.ConvertSidToStringSidW(sid, ctypes.byref(sid_text)):
                raise OSError("无法获取服务账号")
            self._sid = sid_text.value
        finally:
            if sid_text:
                self._kernel.LocalFree(sid_text)
            self._kernel.CloseHandle(token)

    def restrict(self, path: Path, *, directory: bool = False) -> None:
        from ctypes import wintypes

        descriptor = wintypes.LPVOID()
        inheritance = "OICI" if directory else ""
        sddl = f"D:P(A;{inheritance};FA;;;{self._sid})"
        if not self._advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl, 1, ctypes.byref(descriptor), None,
        ):
            raise OSError("无法配置私有权限")
        try:
            present, defaulted = wintypes.BOOL(), wintypes.BOOL()
            dacl = wintypes.LPVOID()
            if not self._advapi.GetSecurityDescriptorDacl(
                descriptor, ctypes.byref(present), ctypes.byref(dacl), ctypes.byref(defaulted),
            ) or not present.value:
                raise OSError("无法配置私有权限")
            result = self._advapi.SetNamedSecurityInfoW(
                str(path), 1, 0x80000004, None, None, dacl, None,
            )
            if result:
                raise OSError("无法配置私有权限")
        finally:
            self._kernel.LocalFree(descriptor)


def _load_file_key(data_root: Path) -> bytes:
    directory = data_root / ".secrets"
    data_root.mkdir(parents=True, exist_ok=True)
    try:
        directory.mkdir(mode=0o700)
    except FileExistsError:
        pass
    _reject_link(directory, directory=True)
    privacy = _WindowsPrivacy() if os.name == "nt" else None
    if privacy is not None:
        privacy.restrict(directory, directory=True)
    else:
        directory.chmod(0o700)
    path = directory / "cache-master.key"
    try:
        _reject_link(path)
    except FileNotFoundError:
        fd, temporary = tempfile.mkstemp(prefix=".cache-master-", dir=directory)
        temporary_path = Path(temporary)
        try:
            with os.fdopen(fd, "wb") as stream:
                if privacy is not None:
                    privacy.restrict(temporary_path)
                else:
                    os.fchmod(stream.fileno(), 0o600)
                stream.write(base64.b64encode(os.urandom(32)))
                stream.flush()
                os.fsync(stream.fileno())
            try:
                os.link(temporary_path, path, follow_symlinks=False)
            except FileExistsError:
                pass
        finally:
            temporary_path.unlink(missing_ok=True)
    _reject_link(path)
    if privacy is not None:
        privacy.restrict(path)
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, "rb") as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size not in (44, 45):
            raise ValueError("主密钥文件大小无效")
        if privacy is None:
            os.fchmod(stream.fileno(), 0o600)
        encoded = stream.read(46)
    if encoded.endswith(b"\n"):
        encoded = encoded[:-1]
    return _decode_key(encoded.decode("ascii"))


class CacheSecretCipher:
    """配置缓存字段的认证加密器。

    @author hanbing
    @since 2026-10-17
    """

    def __init__(self, key: bytes) -> None:
        if len(key) != 32:
            raise ValueError("主密钥长度无效")
        self._cipher = AESGCM(key)

    @classmethod
    def load(
        cls, data_root: Path, environ: Mapping[str, str] | None = None,
    ) -> CacheSecretCipher:
        environment = os.environ if environ is None else environ
        try:
            key = (
                _decode_key(environment[MASTER_KEY_ENV])
                if MASTER_KEY_ENV in environment else _load_file_key(data_root)
            )
            return cls(key)
        except Exception:
            raise RuntimeError("缓存主密钥初始化失败") from None

    @staticmethod
    def _aad(cache_key: str, path: tuple[str, ...]) -> bytes:
        segments = (_AAD_DOMAIN, cache_key, *path)
        encoded = [segment.encode("utf-8") for segment in segments]
        return b"".join(struct.pack(">I", len(segment)) + segment for segment in encoded)

    def encrypt(self, plaintext: str, cache_key: str, *path: str) -> str:
        nonce = os.urandom(12)
        ciphertext = self._cipher.encrypt(
            nonce, plaintext.encode("utf-8"), self._aad(cache_key, path),
        )
        return _PREFIX + base64.b64encode(nonce + ciphertext).decode("ascii")

    def decrypt(self, encoded: str, cache_key: str, *path: str) -> str:
        if not encoded.startswith(_PREFIX):
            raise ValueError("缓存密文版本无效")
        data = _base64_decode(encoded[len(_PREFIX):])
        if len(data) < 28:
            raise ValueError("缓存密文长度无效")
        return self._cipher.decrypt(data[:12], data[12:], self._aad(cache_key, path)).decode("utf-8")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("缓存字段重复")
        result[key] = value
    return result


def decode_envelope(raw: Any) -> dict[str, Any]:
    envelope = json.loads(raw, object_pairs_hook=_unique_object)
    if (
        not isinstance(envelope, dict)
        or set(envelope) != {"version", "payload"}
        or type(envelope["version"]) is not int
        or envelope["version"] != 1
        or not isinstance(envelope["payload"], dict)
    ):
        raise ValueError("缓存信封无效")
    return envelope["payload"]


def encode_envelope(payload: dict[str, Any]) -> str:
    return json.dumps({"version": 1, "payload": payload}, ensure_ascii=False, allow_nan=False)


COMPARE_DELETE_SCRIPT = (
    "if redis.call('get', KEYS[1]) == ARGV[1] then "
    "return redis.call('del', KEYS[1]) else return 0 end"
)
