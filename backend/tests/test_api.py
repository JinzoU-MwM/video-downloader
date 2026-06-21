import pytest
from fastapi.testclient import TestClient

from app import proxy
from app.main import app

client = TestClient(app)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200 and r.json()["status"] == "ok"


def test_extract_requires_key():
    r = client.post("/extract", json={"url": "https://tiktok.com/x"})
    assert r.status_code == 401


def test_proxy_token_roundtrip():
    tok = proxy.sign({"url": "https://cdn/x.mp4", "headers": {}})
    data = proxy.verify(tok)
    assert data["url"] == "https://cdn/x.mp4"


def test_proxy_rejects_tampered_token():
    with pytest.raises(ValueError):
        proxy.verify("not.a.valid.token")
