import os
import subprocess

import pytest
from fastapi.testclient import TestClient

import app.main as main

client = TestClient(main.app)
KEY = {"X-API-Key": "dev-key"}


def _token_query(url="https://www.tiktok.com/@u/video/1"):
    r = client.post("/extract", json={"url": url}, headers=KEY)
    assert r.status_code == 200
    full = r.json()["video"]["url"]
    return full.split("/media")[1]  # "?token=..."


def test_extract_returns_media_url():
    r = client.post("/extract", json={"url": "https://www.tiktok.com/@u/video/1"}, headers=KEY)
    assert r.status_code == 200
    body = r.json()
    assert body["platform"] == "TIKTOK"
    assert body["title"] is None
    assert body["thumbnail"] is None
    assert "/media?token=" in body["video"]["url"]


def test_extract_rejects_unsupported():
    r = client.post("/extract", json={"url": "https://youtube.com/watch?v=1"}, headers=KEY)
    assert r.status_code == 422


def test_extract_requires_key():
    r = client.post("/extract", json={"url": "https://www.tiktok.com/@u/video/1"})
    assert r.status_code == 401


def test_media_bad_quality():
    r = client.get(f"/media{_token_query()}&quality=999&mode=video")
    assert r.status_code == 400


def test_media_bad_mode():
    r = client.get(f"/media{_token_query()}&quality=720&mode=foo")
    assert r.status_code == 400


def test_media_bad_wa():
    r = client.get(f"/media{_token_query()}&quality=720&mode=video&wa=2")
    assert r.status_code == 400


def test_media_success(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))

    def fake_dl(page_url, quality, mode, wa, path):
        with open(path, "wb") as f:
            f.write(b"FAKEDATA")

    monkeypatch.setattr(main, "_download_via_cobalt", fake_dl)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video")
    assert r.status_code == 200
    assert r.content == b"FAKEDATA"
    assert r.headers["content-type"].startswith("video/mp4")


def test_media_wa_flag_passed_through(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))
    seen = {}

    def fake_dl(page_url, quality, mode, wa, path):
        seen["wa"] = wa
        open(path, "wb").write(b"X")

    monkeypatch.setattr(main, "_download_via_cobalt", fake_dl)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video&wa=1")
    assert r.status_code == 200
    assert seen["wa"] is True


def test_media_audio_content_type(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))
    monkeypatch.setattr(main, "_download_via_cobalt",
                        lambda page_url, quality, mode, wa, path: open(path, "wb").write(b"A"))
    r = client.get(f"/media{_token_query()}&quality=720&mode=audio")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("audio/mpeg")


def test_media_cobalt_error(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))

    def boom(page_url, quality, mode, wa, path):
        raise main.cobalt.CobaltError("slideshow not supported")

    monkeypatch.setattr(main, "_download_via_cobalt", boom)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video")
    assert r.status_code == 422


def test_download_empty_body_raises(tmp_path, monkeypatch):
    monkeypatch.setattr(main.cobalt, "resolve",
                        lambda u, q, m: {"kind": "tunnel", "url": "http://x", "filename": "f"})
    monkeypatch.setattr(main, "_fetch", lambda url, dst: open(dst, "wb").close())  # 0 bytes
    out = str(tmp_path / "o.mp4")
    with pytest.raises(main.cobalt.CobaltError):
        main._download_via_cobalt("u", "720", "video", False, out)
    assert not os.path.exists(out)  # never cached an empty file


def test_download_transcode_failure_raises(tmp_path, monkeypatch):
    monkeypatch.setattr(main.cobalt, "resolve",
                        lambda u, q, m: {"kind": "tunnel", "url": "http://x", "filename": "f"})
    monkeypatch.setattr(main, "_fetch", lambda url, dst: open(dst, "wb").write(b"DATA"))

    def boom(src, dst):
        raise subprocess.CalledProcessError(183, ["ffmpeg"])

    monkeypatch.setattr(main.transcode, "transcode_whatsapp", boom)
    out = str(tmp_path / "o.mp4")
    with pytest.raises(main.cobalt.CobaltError):
        main._download_via_cobalt("u", "720", "video", True, out)


def test_download_fetch_http_error_raises(tmp_path, monkeypatch):
    monkeypatch.setattr(main.cobalt, "resolve",
                        lambda u, q, m: {"kind": "tunnel", "url": "http://x", "filename": "f"})

    def bad_fetch(url, dst):
        raise main.httpx.ConnectError("boom")

    monkeypatch.setattr(main, "_fetch", bad_fetch)
    out = str(tmp_path / "o.mp4")
    with pytest.raises(main.cobalt.CobaltError):
        main._download_via_cobalt("u", "720", "video", False, out)
    assert not os.path.exists(out)


def test_download_video_success(tmp_path, monkeypatch):
    monkeypatch.setattr(main.cobalt, "resolve",
                        lambda u, q, m: {"kind": "tunnel", "url": "http://x", "filename": "f"})
    monkeypatch.setattr(main, "_fetch", lambda url, dst: open(dst, "wb").write(b"VIDEOBYTES"))
    out = str(tmp_path / "o.mp4")
    main._download_via_cobalt("u", "720", "video", False, out)
    assert open(out, "rb").read() == b"VIDEOBYTES"
