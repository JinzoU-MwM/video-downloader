import httpx

from .config import settings


class CobaltError(Exception):
    pass


QUALITIES = {"max", "4320", "2160", "1440", "1080", "720", "480", "360", "240", "144"}
MODES = {"video", "audio"}

_HEADERS = {"Accept": "application/json", "Content-Type": "application/json"}


def _post(body: dict) -> dict:
    with httpx.Client(timeout=60) as c:
        r = c.post(settings.cobalt_api_url, json=body, headers=_HEADERS)
    return r.json()


def resolve(url: str, quality: str, mode: str) -> dict:
    """Ask Cobalt for the final media URL. Returns {kind, url, filename} or raises."""
    body: dict = {"url": url, "filenameStyle": "basic", "alwaysProxy": True}
    if mode == "audio":
        body["downloadMode"] = "audio"
        body["audioFormat"] = "mp3"
    else:
        body["downloadMode"] = "auto"
        body["videoQuality"] = quality

    try:
        data = _post(body)
    except Exception as e:  # network / JSON failure
        raise CobaltError(f"cobalt unreachable: {e}")

    status = data.get("status")
    if status in ("tunnel", "redirect"):
        return {"kind": status, "url": data["url"], "filename": data.get("filename")}
    if status == "picker":
        # TikTok slideshow: only the audio track is supported in v1.
        if mode == "audio" and data.get("audio"):
            return {"kind": "tunnel", "url": data["audio"], "filename": data.get("audioFilename")}
        raise CobaltError("slideshow not supported")
    if status == "error":
        code = (data.get("error") or {}).get("code", "unknown")
        raise CobaltError(f"cobalt error: {code}")
    raise CobaltError(f"unexpected cobalt status: {status}")


def health() -> dict:
    try:
        with httpx.Client(timeout=5) as c:
            r = c.get(settings.cobalt_api_url)
        data = r.json()
        return {"reachable": True, "version": (data.get("cobalt") or {}).get("version")}
    except Exception:
        return {"reachable": False, "version": None}
