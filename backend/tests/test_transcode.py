import app.transcode as transcode


def test_whatsapp_args_has_key_flags():
    args = transcode.whatsapp_args("in.mp4", "out.mp4")
    assert args[0] == "ffmpeg"
    assert args[-1] == "out.mp4"
    assert "libx264" in args
    assert "yuv420p" in args
    assert "aac" in args
    assert "-movflags" in args
    assert "+faststart" in args
