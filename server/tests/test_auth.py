"""Auth tests (Phase 6, Priority 6.17): valid token, expired token, invalid token."""
from __future__ import annotations

import re
import time

import pytest
from httpx import AsyncClient
from jose import jwt

from app.config import get_settings
from tests.conftest import CapturingOtpProvider

pytestmark = pytest.mark.asyncio


def _extract_code(message: str) -> str:
    match = re.search(r"\b(\d{6})\b", message)
    assert match, f"No 6-digit code found in OTP message: {message!r}"
    return match.group(1)


async def test_register_then_login(client: AsyncClient):
    email, password = "newuser@example.com", "s3cret-passw0rd"
    register_resp = await client.post("/auth/register", json={"email": email, "password": password})
    assert register_resp.status_code == 200
    assert register_resp.json()["accessToken"]

    login_resp = await client.post("/auth/token", json={"email": email, "password": password})
    assert login_resp.status_code == 200
    assert login_resp.json()["accessToken"]


async def test_login_wrong_password_rejected(client: AsyncClient, registered_user: dict):
    resp = await client.post("/auth/token", json={"email": registered_user["email"], "password": "wrong-password"})
    assert resp.status_code == 401
    assert resp.json()["code"] == "AUTHENTICATION_REQUIRED"


async def test_valid_token_accepted(client: AsyncClient, auth_headers: dict):
    resp = await client.get("/reports/trial-balance", params={"companyId": "COMP_X", "financialYearId": "FY_X"}, headers=auth_headers)
    # Auto-provisions COMP_X as a brand new company for this user (see tenant.py bootstrap rule) -
    # the point of this assertion is that a VALID token is accepted (200), not any particular report content.
    assert resp.status_code == 200


async def test_missing_token_rejected(client: AsyncClient):
    resp = await client.get("/reports/trial-balance", params={"companyId": "COMP_X", "financialYearId": "FY_X"})
    assert resp.status_code == 401
    assert resp.json()["code"] == "AUTHENTICATION_REQUIRED"


async def test_invalid_token_rejected(client: AsyncClient):
    resp = await client.get(
        "/reports/trial-balance",
        params={"companyId": "COMP_X", "financialYearId": "FY_X"},
        headers={"Authorization": "Bearer not-a-real-token"},
    )
    assert resp.status_code == 401
    assert resp.json()["code"] == "AUTHENTICATION_REQUIRED"


async def test_expired_token_rejected(client: AsyncClient, registered_user: dict):
    settings = get_settings()
    expired_payload = {"sub": "some-user-id", "type": "access", "iat": int(time.time()) - 3600, "exp": int(time.time()) - 1800}
    expired_token = jwt.encode(expired_payload, settings.jwt_secret_key, algorithm=settings.jwt_algorithm)

    resp = await client.get(
        "/reports/trial-balance",
        params={"companyId": "COMP_X", "financialYearId": "FY_X"},
        headers={"Authorization": f"Bearer {expired_token}"},
    )
    assert resp.status_code == 401
    assert resp.json()["code"] == "AUTHENTICATION_REQUIRED"


async def test_refresh_token_rotates_and_old_one_stops_working(client: AsyncClient, registered_user: dict):
    refresh_resp = await client.post("/auth/refresh", json={"refreshToken": registered_user["refreshToken"]})
    assert refresh_resp.status_code == 200
    new_tokens = refresh_resp.json()
    assert new_tokens["refreshToken"] != registered_user["refreshToken"]

    # The old refresh token was revoked by rotation - reusing it must fail.
    reuse_resp = await client.post("/auth/refresh", json={"refreshToken": registered_user["refreshToken"]})
    assert reuse_resp.status_code == 401


async def test_logout_revokes_refresh_token(client: AsyncClient, registered_user: dict):
    await client.post("/auth/logout", json={"refreshToken": registered_user["refreshToken"]})
    resp = await client.post("/auth/refresh", json={"refreshToken": registered_user["refreshToken"]})
    assert resp.status_code == 401


# ==================== Phone/OTP login (Week 1, Play Store update plan) ====================


async def test_otp_request_then_verify_logs_in_and_auto_provisions_user(client: AsyncClient, otp_provider: CapturingOtpProvider):
    phone = "+919876543210"
    request_resp = await client.post("/auth/otp/request", json={"phone": phone})
    assert request_resp.status_code == 200
    assert request_resp.json()["expiresInSeconds"] > 0

    assert len(otp_provider.sent) == 1
    sent_phone, message = otp_provider.sent[0]
    assert sent_phone == phone
    code = _extract_code(message)

    verify_resp = await client.post("/auth/otp/verify", json={"phone": phone, "code": code})
    assert verify_resp.status_code == 200
    tokens = verify_resp.json()
    assert tokens["accessToken"]

    # The newly auto-provisioned phone user can use the token like any other.
    report_resp = await client.get(
        "/reports/trial-balance",
        params={"companyId": "COMP_OTP", "financialYearId": "FY_OTP"},
        headers={"Authorization": f"Bearer {tokens['accessToken']}"},
    )
    assert report_resp.status_code == 200


async def test_otp_verify_wrong_code_rejected(client: AsyncClient, otp_provider: CapturingOtpProvider):
    phone = "+919876500001"
    await client.post("/auth/otp/request", json={"phone": phone})
    _, message = otp_provider.sent[0]
    real_code = _extract_code(message)
    wrong_code = f"{(int(real_code) + 1) % 1_000_000:06d}"

    resp = await client.post("/auth/otp/verify", json={"phone": phone, "code": wrong_code})
    assert resp.status_code == 400
    assert resp.json()["code"] == "OTP_INVALID_OR_EXPIRED"


async def test_otp_verify_with_no_request_rejected(client: AsyncClient):
    resp = await client.post("/auth/otp/verify", json={"phone": "+919876500002", "code": "123456"})
    assert resp.status_code == 400
    assert resp.json()["code"] == "OTP_INVALID_OR_EXPIRED"


async def test_otp_request_rate_limited_within_cooldown(client: AsyncClient, otp_provider: CapturingOtpProvider):
    phone = "+919876500003"
    first_resp = await client.post("/auth/otp/request", json={"phone": phone})
    assert first_resp.status_code == 200

    second_resp = await client.post("/auth/otp/request", json={"phone": phone})
    assert second_resp.status_code == 429
    assert second_resp.json()["code"] == "OTP_RATE_LIMITED"
    assert len(otp_provider.sent) == 1


async def test_otp_request_invalid_phone_rejected(client: AsyncClient):
    resp = await client.post("/auth/otp/request", json={"phone": "not-a-phone"})
    assert resp.status_code == 400
    assert resp.json()["code"] == "VALIDATION_ERROR"
