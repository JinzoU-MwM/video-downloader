from app.extractor import detect_platform


def test_detect_tiktok():
    assert detect_platform("https://www.tiktok.com/@u/video/123") == "TIKTOK"
    assert detect_platform("https://vm.tiktok.com/ABC123/") == "TIKTOK"


def test_detect_instagram():
    assert detect_platform("https://www.instagram.com/reel/Cabc/") == "INSTAGRAM"
    assert detect_platform("https://instagram.com/p/Cxyz/") == "INSTAGRAM"


def test_detect_facebook():
    assert detect_platform("https://www.facebook.com/watch/?v=123") == "FACEBOOK"
    assert detect_platform("https://fb.watch/abc/") == "FACEBOOK"


def test_detect_none():
    assert detect_platform("https://youtube.com/watch?v=1") is None
