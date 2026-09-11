"""
Pluggable SMS delivery for OTP login (Week 1, Play Store update plan). No real SMS gateway is
wired up yet - a real deployment picks one (MSG91/2Factor/Twilio/etc.), implements
[OtpSmsProvider] against its API, and sets OTP_SMS_PROVIDER to select it in [get_otp_provider].
Everything else in the OTP flow (request/verify routes, rate limiting, hashing, expiry) is
provider-independent and already fully real - only the actual "send a text message" step is
stubbed.
"""
from __future__ import annotations

from abc import ABC, abstractmethod


class OtpSmsProvider(ABC):
    @abstractmethod
    async def send(self, phone: str, message: str) -> None:
        """Send `message` (already fully formatted, including the SMS Retriever app-hash suffix
        when configured) to `phone`. Must raise on delivery failure rather than fail silently -
        the caller needs to know whether the user can actually expect a text."""


class ConsoleOtpProvider(OtpSmsProvider):
    """Default/dev provider - logs to stdout instead of sending a real SMS. Needs no vendor
    account, so the whole OTP flow is testable end-to-end (read the code from the server log)
    before any real gateway is chosen."""

    async def send(self, phone: str, message: str) -> None:
        print(f"[OTP SMS -> {phone}] {message}")


def get_otp_provider() -> OtpSmsProvider:
    """FastAPI dependency (`Depends(get_otp_provider)`) - tests override this the same way they
    override `get_db`, with a fake that captures the sent code instead of a real network call."""
    from app.config import get_settings

    settings = get_settings()
    if settings.otp_sms_provider == "console":
        return ConsoleOtpProvider()
    raise NotImplementedError(
        f"No OtpSmsProvider implementation registered for otp_sms_provider={settings.otp_sms_provider!r} - "
        "add one in app/infrastructure/sms/provider.py and wire it in here."
    )
