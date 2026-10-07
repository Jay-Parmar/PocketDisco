import asyncio
from contextlib import asynccontextmanager

from sqlalchemy import event, text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

from .config import Settings
from .models import Base


class Database:
    def __init__(self, settings: Settings):
        self.local_test = settings.mode == "local_test"
        options = {"pool_pre_ping": True, "hide_parameters": True}
        if not self.local_test:
            options.update(
                pool_size=settings.database_pool_size,
                max_overflow=settings.database_max_overflow,
                pool_timeout=settings.database_pool_timeout_seconds,
                connect_args={
                    "timeout": settings.database_connect_seconds,
                    "command_timeout": settings.database_statement_seconds,
                    "server_settings": {
                        "statement_timeout": str(settings.database_statement_seconds * 1000),
                        "lock_timeout": str(settings.database_lock_seconds * 1000),
                        "idle_in_transaction_session_timeout": "10000",
                    },
                },
            )
        self.engine = create_async_engine(settings.database_url, **options)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)
        self.local_lock = asyncio.Lock()
        if self.local_test:
            event.listen(self.engine.sync_engine, "connect", self._sqlite_foreign_keys)

    @staticmethod
    def _sqlite_foreign_keys(connection, _):
        connection.execute("PRAGMA foreign_keys=ON")

    async def start(self):
        async with self.engine.begin() as connection:
            if self.local_test:
                await connection.run_sync(Base.metadata.create_all)
            else:
                await connection.execute(text("SELECT 1 FROM users LIMIT 1"))

    async def close(self):
        await self.engine.dispose()

    @asynccontextmanager
    async def transaction(self):
        if self.local_test:
            async with self.local_lock, self.sessions.begin() as session:
                yield session
        else:
            async with self.sessions.begin() as session:
                yield session
