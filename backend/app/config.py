import os


class Settings:
    api_key: str = os.environ.get("API_KEY", "dev-key")
    hmac_secret: str = os.environ.get("HMAC_SECRET", "dev-secret")
    proxy_token_ttl: int = int(os.environ.get("PROXY_TOKEN_TTL", "3600"))


settings = Settings()
