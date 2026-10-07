import asyncio

import pytest
from sqlalchemy import select

from pocketdisco.auth import Auth, token_hash
from pocketdisco.errors import ApiError
from pocketdisco.models import AccessToken, AuthSession, RefreshToken


async def test_guest_stores_only_hashed_tokens(database, settings):
    auth = Auth(database, settings)
    result = await auth.guest("Mira")
    identity = await auth.authenticate(result.access_token)
    assert identity.user_id == result.user.id
    async with database.sessions() as db:
        access = await db.scalar(select(AccessToken))
        refresh = await db.scalar(select(RefreshToken))
        assert access.token_hash == token_hash(result.access_token)
        assert refresh.token_hash == token_hash(result.refresh_token)
    assert 898 <= result.expires_in <= 900


async def test_refresh_rotates_and_reuse_revokes_family(database, settings):
    auth = Auth(database, settings)
    first = await auth.guest("Mira")
    second = await auth.refresh(first.refresh_token)
    assert second.refresh_token != first.refresh_token
    assert second.user == first.user
    await auth.authenticate(second.access_token)
    with pytest.raises(ApiError):
        await auth.refresh(first.refresh_token)
    for token in (first.access_token, second.access_token):
        with pytest.raises(ApiError):
            await auth.authenticate(token)
    with pytest.raises(ApiError):
        await auth.refresh(second.refresh_token)


async def test_reuse_does_not_revoke_other_user(database, settings):
    auth = Auth(database, settings)
    first = await auth.guest("Mira")
    other = await auth.guest("Sam")
    await auth.refresh(first.refresh_token)
    with pytest.raises(ApiError):
        await auth.refresh(first.refresh_token)
    assert (await auth.authenticate(other.access_token)).display_name == "Sam"


async def test_concurrent_refresh_has_one_winner_then_revokes(database, settings):
    auth = Auth(database, settings)
    first = await auth.guest("Mira")
    results = await asyncio.gather(
        auth.refresh(first.refresh_token), auth.refresh(first.refresh_token), return_exceptions=True
    )
    assert sum(isinstance(result, ApiError) for result in results) == 1
    with pytest.raises(ApiError):
        await auth.authenticate(first.access_token)


async def test_expired_session_cannot_refresh_or_authenticate(database, settings):
    auth = Auth(database, settings)
    first = await auth.guest("Mira")
    async with database.transaction() as db:
        session = await db.scalar(select(AuthSession))
        session.expires_at_ms = 0
    with pytest.raises(ApiError):
        await auth.refresh(first.refresh_token)
    with pytest.raises(ApiError):
        await auth.authenticate(first.access_token)


async def test_expired_access_can_still_refresh(database, settings):
    auth = Auth(database, settings)
    first = await auth.guest("Mira")
    async with database.transaction() as db:
        access = await db.scalar(select(AccessToken))
        access.expires_at_ms = 0
    with pytest.raises(ApiError):
        await auth.authenticate(first.access_token)
    second = await auth.refresh(first.refresh_token)
    await auth.authenticate(second.access_token)


async def test_unknown_tokens_fail(database, settings):
    auth = Auth(database, settings)
    with pytest.raises(ApiError):
        await auth.authenticate("not-a-token")
    with pytest.raises(ApiError):
        await auth.refresh("not-a-token")
