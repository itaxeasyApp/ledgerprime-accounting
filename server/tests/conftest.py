"""
Pytest fixtures (Phase 6, Priority 6.17). Runs against an in-memory SQLite database via the same
SQLAlchemy async engine/models the production code uses - this development environment has no
Docker/Postgres available (documented in the Phase 6 plan), so tests verify the same schema and
business logic against SQLite instead of skipping automated verification entirely. Each test gets
a fresh database (tables created and dropped per test) so tests never leak state into each other.
"""
from __future__ import annotations

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.infrastructure.database.base import Base, get_db, make_engine
from app.infrastructure.sms.provider import OtpSmsProvider, get_otp_provider
from app.main import app


class CapturingOtpProvider(OtpSmsProvider):
    """Test double for OTP SMS - records every (phone, message) instead of sending anything real,
    so a test can read back the actual code without scraping stdout."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []

    async def send(self, phone: str, message: str) -> None:
        self.sent.append((phone, message))


@pytest_asyncio.fixture
async def db_engine():
    engine = make_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    await engine.dispose()


@pytest_asyncio.fixture
async def db_session(db_engine) -> AsyncSession:
    from sqlalchemy.ext.asyncio import async_sessionmaker

    session_factory = async_sessionmaker(bind=db_engine, expire_on_commit=False, class_=AsyncSession)
    async with session_factory() as session:
        yield session


@pytest.fixture
def otp_provider() -> CapturingOtpProvider:
    return CapturingOtpProvider()


@pytest_asyncio.fixture
async def client(db_engine, otp_provider: CapturingOtpProvider):
    from sqlalchemy.ext.asyncio import async_sessionmaker

    session_factory = async_sessionmaker(bind=db_engine, expire_on_commit=False, class_=AsyncSession)

    async def _override_get_db():
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db] = _override_get_db
    app.dependency_overrides[get_otp_provider] = lambda: otp_provider
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test/api/v1") as ac:
        yield ac
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def registered_user(client: AsyncClient) -> dict:
    """Registers a fresh user and returns {email, password, accessToken, refreshToken}."""
    email = "owner@example.com"
    password = "correct horse battery staple"
    response = await client.post("/auth/register", json={"email": email, "password": password})
    assert response.status_code == 200, response.text
    tokens = response.json()
    return {"email": email, "password": password, **tokens}


@pytest_asyncio.fixture
async def auth_headers(registered_user: dict) -> dict:
    return {"Authorization": f"Bearer {registered_user['accessToken']}"}
