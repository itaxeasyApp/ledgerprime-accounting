"""
Authentication routes (Phase 6, Priority 6.9). `/auth/register` exists because this system has no
separate provisioning flow - a user creates their own account, then the first company they sync
auto-grants them OWNER (see `require_company_access`'s bootstrap rule). Passwords are hashed with
bcrypt before ever touching the database; refresh tokens are stored only as a SHA-256 hash.
"""
from __future__ import annotations

import re
import secrets
import time
import uuid

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.domain.errors import AuthenticationRequired, OtpInvalidOrExpired, OtpRateLimited, ValidationError
from app.infrastructure.database.base import get_db
from app.infrastructure.database.models import OtpCode, RefreshToken, User
from app.infrastructure.security.jwt import (
    create_access_token,
    create_refresh_token_plaintext,
    hash_refresh_token,
)
from app.infrastructure.security.passwords import hash_password, verify_password
from app.infrastructure.sms.provider import OtpSmsProvider, get_otp_provider
from app.schemas.auth import (
    AuthTokenResponse,
    LoginRequest,
    OtpRequestRequest,
    OtpRequestResponse,
    OtpVerifyRequest,
    RefreshRequest,
)

router = APIRouter(prefix="/auth", tags=["auth"])

_PHONE_PATTERN = re.compile(r"^\+?[0-9]{8,15}$")


def _normalize_phone(raw: str) -> str:
    phone = raw.strip().replace(" ", "").replace("-", "")
    if not _PHONE_PATTERN.match(phone):
        raise ValidationError("Enter a valid phone number (8-15 digits, optional leading '+').")
    return phone


async def _issue_tokens(db: AsyncSession, user_id: str) -> AuthTokenResponse:
    access_token, expires_in = create_access_token(user_id)
    refresh_token = create_refresh_token_plaintext()
    now = int(time.time())
    from app.config import get_settings

    settings = get_settings()
    db.add(
        RefreshToken(
            id=uuid.uuid4().hex,
            user_id=user_id,
            token_hash=hash_refresh_token(refresh_token),
            expires_at=now + settings.jwt_refresh_token_expire_days * 86400,
            revoked=False,
            created_at=now * 1000,
        )
    )
    return AuthTokenResponse(accessToken=access_token, refreshToken=refresh_token, expiresInSeconds=expires_in)


@router.post("/register", response_model=AuthTokenResponse)
async def register(request: LoginRequest, db: AsyncSession = Depends(get_db)):
    existing = await db.execute(select(User).where(User.email == request.email))
    if existing.scalar_one_or_none() is not None:
        raise ValidationError("An account with this email already exists.")

    user = User(user_id=uuid.uuid4().hex, email=request.email, hashed_password=hash_password(request.password), created_at=int(time.time() * 1000))
    db.add(user)
    await db.flush()
    tokens = await _issue_tokens(db, user.user_id)
    await db.commit()
    return tokens


@router.post("/token", response_model=AuthTokenResponse)
async def login(request: LoginRequest, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(User).where(User.email == request.email))
    user = result.scalar_one_or_none()
    if user is None or not verify_password(request.password, user.hashed_password):
        raise AuthenticationRequired("Invalid email or password.")

    tokens = await _issue_tokens(db, user.user_id)
    await db.commit()
    return tokens


@router.post("/refresh", response_model=AuthTokenResponse)
async def refresh(request: RefreshRequest, db: AsyncSession = Depends(get_db)):
    token_hash = hash_refresh_token(request.refreshToken)
    result = await db.execute(select(RefreshToken).where(RefreshToken.token_hash == token_hash))
    stored = result.scalar_one_or_none()
    if stored is None or stored.revoked or stored.expires_at < int(time.time()):
        raise AuthenticationRequired("Refresh token is invalid, revoked, or expired.")

    # Rotate: revoke the old refresh token, issue a brand new pair.
    stored.revoked = True
    tokens = await _issue_tokens(db, stored.user_id)
    await db.commit()
    return tokens


@router.post("/logout")
async def logout(request: RefreshRequest, db: AsyncSession = Depends(get_db)):
    token_hash = hash_refresh_token(request.refreshToken)
    result = await db.execute(select(RefreshToken).where(RefreshToken.token_hash == token_hash))
    stored = result.scalar_one_or_none()
    if stored is not None:
        stored.revoked = True
        await db.commit()
    return {}


def _format_otp_message(code: str) -> str:
    settings = get_settings()
    minutes = settings.otp_expiry_seconds // 60
    if settings.android_sms_retriever_hash:
        # Google's SMS Retriever API format: starts with "<#>", ends with the requesting app's
        # 11-character signature hash on its own line - this exact shape is what lets Android
        # auto-detect and auto-fill the code with no SMS permission at all.
        return f"<#> Your LedgerPrime OTP is {code}. It expires in {minutes} minutes.\n{settings.android_sms_retriever_hash}"
    return f"Your LedgerPrime OTP is {code}. It expires in {minutes} minutes."


@router.post("/otp/request", response_model=OtpRequestResponse)
async def request_otp(
    request: OtpRequestRequest,
    db: AsyncSession = Depends(get_db),
    sms: OtpSmsProvider = Depends(get_otp_provider),
):
    phone = _normalize_phone(request.phone)
    settings = get_settings()
    now = int(time.time())

    result = await db.execute(
        select(OtpCode).where(OtpCode.phone == phone).order_by(OtpCode.created_at.desc()).limit(1)
    )
    latest = result.scalar_one_or_none()
    if latest is not None and not latest.consumed and now - latest.created_at < settings.otp_resend_cooldown_seconds:
        raise OtpRateLimited(f"Wait {settings.otp_resend_cooldown_seconds - (now - latest.created_at)}s before requesting another code.")

    code = f"{secrets.randbelow(1_000_000):06d}"
    db.add(
        OtpCode(
            otp_id=uuid.uuid4().hex,
            phone=phone,
            code_hash=hash_password(code),
            expires_at=now + settings.otp_expiry_seconds,
            attempts=0,
            consumed=False,
            created_at=now,
        )
    )
    await sms.send(phone, _format_otp_message(code))
    await db.commit()
    return OtpRequestResponse(expiresInSeconds=settings.otp_expiry_seconds)


@router.post("/otp/verify", response_model=AuthTokenResponse)
async def verify_otp(request: OtpVerifyRequest, db: AsyncSession = Depends(get_db)):
    phone = _normalize_phone(request.phone)
    settings = get_settings()
    now = int(time.time())

    result = await db.execute(
        select(OtpCode).where(OtpCode.phone == phone, OtpCode.consumed.is_(False)).order_by(OtpCode.created_at.desc()).limit(1)
    )
    otp = result.scalar_one_or_none()
    if otp is None or now > otp.expires_at or otp.attempts >= settings.otp_max_attempts:
        raise OtpInvalidOrExpired("This code is invalid or has expired - request a new one.")

    if not verify_password(request.code, otp.code_hash):
        otp.attempts += 1
        await db.commit()
        raise OtpInvalidOrExpired("This code is invalid or has expired - request a new one.")

    otp.consumed = True

    user_result = await db.execute(select(User).where(User.phone == phone))
    user = user_result.scalar_one_or_none()
    if user is None:
        # Auto-provision on first successful OTP - same "no separate provisioning flow" philosophy
        # as /register: a verified phone number is proof enough of identity to create the account.
        user = User(user_id=uuid.uuid4().hex, phone=phone, email=None, hashed_password=None, created_at=now * 1000)
        db.add(user)
        await db.flush()

    tokens = await _issue_tokens(db, user.user_id)
    await db.commit()
    return tokens
