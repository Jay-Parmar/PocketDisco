from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from redis.exceptions import RedisError
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from .auth import Auth, Identity, token_hash
from .config import Settings
from .database import Database
from .errors import ApiError, unauthorized
from .http import BodyLimit
from .live import create_live
from .realtime import register_realtime
from .rooms import Rooms
from .schemas import GuestRequest, RefreshRequest, RoomRequest, SessionView, Snapshot, TicketRequest

bearer = HTTPBearer(auto_error=False)


def create_app(settings: Settings | None = None):
    settings = settings or Settings()
    database = Database(settings)
    live = create_live(settings)
    auth = Auth(database, settings)
    rooms = Rooms(database, live, settings)

    @asynccontextmanager
    async def lifespan(app):
        try:
            await database.start()
            await live.start()
            yield
        finally:
            await live.close()
            await database.close()

    app = FastAPI(title="PocketDisco", version="0.1.0", lifespan=lifespan)
    app.add_middleware(BodyLimit)
    app.state.database = database
    app.state.live = live
    app.state.auth = auth
    app.state.rooms = rooms

    @app.exception_handler(ApiError)
    async def api_error(request, error):
        headers = {"Cache-Control": "no-store"}
        if error.status == 401:
            headers["WWW-Authenticate"] = "Bearer"
        return JSONResponse({"detail": error.detail()}, status_code=error.status, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        return JSONResponse(
            {"detail": {"code": "invalid_request", "message": "Check the supplied fields."}},
            status_code=422,
        )

    @app.exception_handler(RedisError)
    @app.exception_handler(SQLAlchemyError)
    async def store_unavailable(request, error):
        return JSONResponse(
            {"detail": {"code": "service_unavailable", "message": "Please try again shortly."}},
            status_code=503,
        )

    @app.middleware("http")
    async def private_responses(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    async def identity(
        credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    ):
        if credentials is None:
            raise unauthorized()
        return await auth.authenticate(credentials.credentials)

    current_user = Annotated[Identity, Depends(identity)]

    async def ip_rate(request, action, limit, seconds):
        address = request.client.host if request.client else "unknown"
        await live.rate_limit(f"{action}:ip:{token_hash(address)}", limit, seconds)

    @app.get("/healthz")
    async def health():
        async with database.transaction() as db:
            await db.execute(text("SELECT 1"))
        await live.start()
        return {"status": "ok", "mode": settings.mode}

    @app.post("/v1/auth/guest", response_model=SessionView, status_code=201)
    async def guest(body: GuestRequest, request: Request):
        await ip_rate(request, "guest", 30, 3600)
        return await auth.guest(body.display_name)

    @app.post("/v1/auth/refresh", response_model=SessionView)
    async def refresh(body: RefreshRequest, request: Request):
        await ip_rate(request, "refresh", 120, 60)
        return await auth.refresh(body.refresh_token)

    @app.post("/v1/rooms", status_code=201)
    async def create_room(body: RoomRequest, user: current_user):
        await live.rate_limit(f"create:{user.user_id}", 10, 3600)
        snapshot, invite = await rooms.create(user, body.name)
        return {"snapshot": snapshot, "invite_code": invite}

    @app.post("/v1/rooms/{invite_code}/join")
    async def join_room(invite_code: str, user: current_user, request: Request):
        await ip_rate(request, "join", 30, 60)
        await live.rate_limit(f"join:{user.user_id}", 20, 60)
        if len(invite_code) > 64:
            raise ApiError(404, "invite_unavailable", "Check the invite code and try again.")
        return {"snapshot": await rooms.join(user, invite_code)}

    @app.get("/v1/rooms/{room_id}/snapshot", response_model=Snapshot)
    async def snapshot(room_id: UUID, user: current_user):
        await live.rate_limit(f"snapshot:{user.user_id}", 120, 60)
        return await rooms.snapshot(user, str(room_id))

    @app.post("/v1/rooms/{room_id}/leave")
    async def leave_room(room_id: UUID, user: current_user):
        await live.rate_limit(f"leave:{user.user_id}", 30, 60)
        await rooms.leave(user, str(room_id))
        return {"ok": True}

    @app.post("/v1/realtime/tickets")
    async def ticket(body: TicketRequest, user: current_user):
        await live.rate_limit(f"tickets:{user.user_id}", 30, 60)
        room_id = str(body.room_id)
        await rooms.authorize(user, room_id)
        value = await live.issue_ticket({"session_id": user.session_id, "room_id": room_id})
        return {"ticket": value, "expires_in": settings.ticket_seconds}

    register_realtime(app, auth, rooms, live)
    return app
