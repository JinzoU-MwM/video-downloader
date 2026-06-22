import re

_PATTERNS = [
    ("TIKTOK", re.compile(r"(tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com)", re.I)),
    ("INSTAGRAM", re.compile(r"instagram\.com", re.I)),
    ("FACEBOOK", re.compile(r"(facebook\.com|fb\.watch|fb\.com)", re.I)),
]


def detect_platform(url: str):
    for name, pat in _PATTERNS:
        if pat.search(url):
            return name
    return None
