import shutil
import subprocess


def whatsapp_args(src: str, dst: str) -> list[str]:
    # Cap the minor axis to 720 without upscaling, keep aspect, force even dims.
    # landscape (iw>ih): height 720, width auto-even; portrait: width 720, height auto-even.
    vf = (
        "scale="
        "w='if(gt(iw,ih),-2,trunc(min(720,iw)/2)*2)':"
        "h='if(gt(iw,ih),trunc(min(720,ih)/2)*2,-2)'"
    )
    return [
        "ffmpeg", "-y", "-i", src,
        "-vf", vf,
        "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-crf", "23", "-preset", "veryfast",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        dst,
    ]


def transcode_whatsapp(src: str, dst: str):
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg not available")
    subprocess.run(whatsapp_args(src, dst), check=True, capture_output=True)
