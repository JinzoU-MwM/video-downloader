import pytest

import app.cobalt as cobalt


def test_resolve_tunnel(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "tunnel", "url": "http://cobalt-api:9000/tunnel?id=1", "filename": "v.mp4"})
    out = cobalt.resolve("https://tiktok.com/x", "720", "video")
    assert out["kind"] == "tunnel"
    assert out["url"] == "http://cobalt-api:9000/tunnel?id=1"


def test_resolve_redirect(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "redirect", "url": "http://cdn/v.mp4", "filename": "v.mp4"})
    out = cobalt.resolve("https://tiktok.com/x", "720", "video")
    assert out["url"] == "http://cdn/v.mp4"


def test_resolve_picker_video_unsupported(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "picker", "picker": [{"type": "photo", "url": "http://x/1.jpg"}]})
    with pytest.raises(cobalt.CobaltError):
        cobalt.resolve("https://tiktok.com/slideshow", "720", "video")


def test_resolve_picker_audio_ok(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "picker", "audio": "http://x/a.mp3", "audioFilename": "a.mp3", "picker": []})
    out = cobalt.resolve("https://tiktok.com/slideshow", "720", "audio")
    assert out["url"] == "http://x/a.mp3"


def test_resolve_error(monkeypatch):
    monkeypatch.setattr(cobalt, "_post", lambda body: {
        "status": "error", "error": {"code": "error.api.fetch.fail"}})
    with pytest.raises(cobalt.CobaltError):
        cobalt.resolve("https://tiktok.com/x", "720", "video")


def test_resolve_unreachable(monkeypatch):
    def boom(body):
        raise RuntimeError("connection refused")
    monkeypatch.setattr(cobalt, "_post", boom)
    with pytest.raises(cobalt.CobaltError):
        cobalt.resolve("https://tiktok.com/x", "720", "video")


def test_request_body_audio(monkeypatch):
    captured = {}
    monkeypatch.setattr(cobalt, "_post",
                        lambda body: captured.update(body) or {"status": "tunnel", "url": "u", "filename": "a.mp3"})
    cobalt.resolve("https://tiktok.com/x", "720", "audio")
    assert captured["downloadMode"] == "audio"
    assert captured["audioFormat"] == "mp3"
    assert captured["alwaysProxy"] is True


def test_request_body_video(monkeypatch):
    captured = {}
    monkeypatch.setattr(cobalt, "_post",
                        lambda body: captured.update(body) or {"status": "tunnel", "url": "u", "filename": "v.mp4"})
    cobalt.resolve("https://tiktok.com/x", "1080", "video")
    assert captured["downloadMode"] == "auto"
    assert captured["videoQuality"] == "1080"
