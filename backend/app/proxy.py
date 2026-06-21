import base64
import hashlib
import hmac
import json
import time

from .config import settings


def _b64e(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def sign(payload: dict) -> str:
    body = dict(payload)
    body["exp"] = int(time.time()) + settings.proxy_token_ttl
    raw = json.dumps(body, separators=(",", ":")).encode()
    mac = hmac.new(settings.hmac_secret.encode(), raw, hashlib.sha256).digest()
    return f"{_b64e(raw)}.{_b64e(mac)}"


def verify(token: str) -> dict:
    try:
        raw_b64, mac_b64 = token.split(".")
        raw = _b64d(raw_b64)
        expected = hmac.new(settings.hmac_secret.encode(), raw, hashlib.sha256).digest()
        if not hmac.compare_digest(expected, _b64d(mac_b64)):
            raise ValueError("bad signature")
        data = json.loads(raw)
        if data.get("exp", 0) < int(time.time()):
            raise ValueError("expired")
        return data
    except ValueError:
        raise
    except Exception as e:
        raise ValueError(str(e))
