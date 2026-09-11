"""
Server configuration (Phase 6, Priority 6.20). Never hardcode secrets in source - every value here
is read from the environment (see .env.example), matching the Android side's own rule of never
storing server secrets in the APK.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    database_url: str = "sqlite+aiosqlite:///./ledgerprime_dev.db"
    jwt_secret_key: str = "change-me-in-a-real-deployment"
    jwt_algorithm: str = "HS256"
    jwt_access_token_expire_minutes: int = 15
    jwt_refresh_token_expire_days: int = 30
    environment: str = "development"

    # Phone/OTP login (Week 1, Play Store update plan). "console" (default) logs the code instead
    # of sending a real SMS - safe for local/dev use with zero vendor setup; a real deployment sets
    # otp_sms_provider to a real gateway's name once one is chosen (see app/infrastructure/sms/).
    otp_sms_provider: str = "console"
    otp_expiry_seconds: int = 300
    otp_max_attempts: int = 5
    otp_resend_cooldown_seconds: int = 60
    # The 11-character app signature hash Android's SMS Retriever API needs appended to the SMS
    # body to auto-detect and auto-fill it (see AppSignatureHelper on the Android side for how this
    # is computed) - blank until that's wired up; OTP SMS still sends fine without it, just without
    # auto-read.
    android_sms_retriever_hash: str = ""


@lru_cache
def get_settings() -> Settings:
    return Settings()
