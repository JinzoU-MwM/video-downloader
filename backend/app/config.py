import os


class Settings:
    api_key: str = os.environ.get("API_KEY", "dev-key")
    hmac_secret: str = os.environ.get("HMAC_SECRET", "dev-secret")
    proxy_token_ttl: int = int(os.environ.get("PROXY_TOKEN_TTL", "3600"))
    public_base_url: str = os.environ.get("PUBLIC_BASE_URL", "https://rdl-api.jni.my.id")
    media_dir: str = os.environ.get("MEDIA_DIR", "/tmp/media")
    media_ttl: int = int(os.environ.get("MEDIA_TTL", "7200"))
    cobalt_api_url: str = os.environ.get("COBALT_API_URL", "http://cobalt-api:9000/")
    app_release_dir: str = os.environ.get("APP_RELEASE_DIR", "/release")


settings = Settings()
