import app.ytdlp_fallback as yf


def test_format_selector_audio():
    assert yf.format_selector("720", "audio") == "bestaudio/best"
    assert yf.format_selector("max", "audio") == "bestaudio/best"


def test_format_selector_video_max():
    assert yf.format_selector("max", "video") == "bv*+ba/b"


def test_format_selector_video_capped():
    sel = yf.format_selector("720", "video")
    assert "height<=720" in sel
    assert "mp4" in sel


def test_is_transient():
    assert yf._is_transient("Unable to extract webpage")
    assert yf._is_transient("HTTP Error 503: Service Unavailable")
    assert not yf._is_transient("Video unavailable: private")
