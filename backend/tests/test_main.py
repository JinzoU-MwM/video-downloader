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

    monkeypatch.setattr(main, "_download", fake_dl)
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

    monkeypatch.setattr(main, "_download", fake_dl)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video&wa=1")
    assert r.status_code == 200
    assert seen["wa"] is True


def test_media_audio_content_type(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))
    monkeypatch.setattr(main, "_download",
                        lambda page_url, quality, mode, wa, path: open(path, "wb").write(b"A"))
    r = client.get(f"/media{_token_query()}&quality=720&mode=audio")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("audio/mpeg")


def test_media_cobalt_error(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "media_dir", str(tmp_path))

    def boom(page_url, quality, mode, wa, path):
        raise main.cobalt.CobaltError("slideshow not supported")

    monkeypatch.setattr(main, "_download", boom)
    r = client.get(f"/media{_token_query()}&quality=720&mode=video")
    assert r.status_code == 422


def _cobalt_ok(monkeypatch, body=b"COBALT"):
    monkeypatch.setattr(main.cobalt, "resolve",
                        lambda u, q, m: {"kind": "tunnel", "url": "http://x", "filename": "f"})
    monkeypatch.setattr(main, "_fetch", lambda url, dst: open(dst, "wb").write(body))


def _ytdlp_writes(body=b"YTDLP"):
    def _dl(url, quality, mode, out_base):
        p = f"{out_base}.ytdl.{'mp3' if mode == 'audio' else 'mp4'}"
        with open(p, "wb") as f:
            f.write(body)
        return p
    return _dl


def test_download_cobalt_success_no_fallback(tmp_path, monkeypatch):
    _cobalt_ok(monkeypatch, b"COBALT")

    def no_call(*a, **k):
        raise AssertionError("yt-dlp must not run when Cobalt succeeds")

    monkeypatch.setattr(main.ytdlp_fallback, "download", no_call)
    out = str(tmp_path / "o.mp4")
    main._download("u", "720", "video", False, out)
    assert open(out, "rb").read() == b"COBALT"


def test_download_falls_back_on_cobalt_error(tmp_path, monkeypatch):
    monkeypatch.setattr(main.cobalt, "resolve",
                        lambda u, q, m: (_ for _ in ()).throw(main.cobalt.CobaltError("fetch.fail")))
    monkeypatch.setattr(main.ytdlp_fallback, "download", _ytdlp_writes(b"YTDLP"))
    out = str(tmp_path / "o.mp4")
    main._download("u", "720", "video", False, out)
    assert open(out, "rb").read() == b"YTDLP"


def test_download_falls_back_when_cobalt_empty(tmp_path, monkeypatch):
    _cobalt_ok(monkeypatch, b"")  # 0-byte cobalt tunnel
    monkeypatch.setattr(main.ytdlp_fallback, "download", _ytdlp_writes(b"YTDLP"))
    out = str(tmp_path / "o.mp4")
    main._download("u", "720", "video", False, out)
    assert open(out, "rb").read() == b"YTDLP"


def test_download_both_fail_raises(tmp_path, monkeypatch):
    monkeypatch.setattr(main.cobalt, "resolve",
                        lambda u, q, m: (_ for _ in ()).throw(main.cobalt.CobaltError("fetch.fail")))

    def boom(url, quality, mode, out_base):
        raise main.ytdlp_fallback.ExtractError("yt-dlp blocked too")

    monkeypatch.setattr(main.ytdlp_fallback, "download", boom)
    out = str(tmp_path / "o.mp4")
    with pytest.raises(main.ytdlp_fallback.ExtractError):
        main._download("u", "720", "video", False, out)
    assert not os.path.exists(out)


def test_download_transcode_failure_raises(tmp_path, monkeypatch):
    _cobalt_ok(monkeypatch, b"DATA")

    def boom(src, dst):
        raise subprocess.CalledProcessError(183, ["ffmpeg"])

    monkeypatch.setattr(main.transcode, "transcode_whatsapp", boom)
    out = str(tmp_path / "o.mp4")
    with pytest.raises(main.cobalt.CobaltError):
        main._download("u", "720", "video", True, out)


def test_download_video_success(tmp_path, monkeypatch):
    _cobalt_ok(monkeypatch, b"VIDEOBYTES")
    out = str(tmp_path / "o.mp4")
    main._download("u", "720", "video", False, out)
    assert open(out, "rb").read() == b"VIDEOBYTES"


def test_app_latest_no_version_file(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "app_release_dir", str(tmp_path))
    r = client.get("/app/latest")
    assert r.status_code == 200
    b = r.json()
    assert b["versionCode"] == 0
    assert b["apkUrl"].endswith("/app/download")


def test_app_latest_reads_version_json(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "app_release_dir", str(tmp_path))
    (tmp_path / "version.json").write_text('{"versionCode": 5, "versionName": "1.5", "notes": "hi"}')
    r = client.get("/app/latest")
    b = r.json()
    assert b["versionCode"] == 5
    assert b["versionName"] == "1.5"
    assert b["notes"] == "hi"


def test_app_download_404_when_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "app_release_dir", str(tmp_path))
    assert client.get("/app/download").status_code == 404


def test_app_download_serves_apk(tmp_path, monkeypatch):
    monkeypatch.setattr(main.settings, "app_release_dir", str(tmp_path))
    (tmp_path / "app.apk").write_bytes(b"PK\x03\x04APKDATA")
    r = client.get("/app/download")
    assert r.status_code == 200
    assert r.content == b"PK\x03\x04APKDATA"
    assert "android.package-archive" in r.headers["content-type"]
