import asyncio

from alembic import context
from sqlalchemy.ext.asyncio import create_async_engine

from pocketdisco.config import Settings
from pocketdisco.models import Base

settings = Settings()


def migrate(connection):
    context.configure(connection=connection, target_metadata=Base.metadata, compare_type=True)
    with context.begin_transaction():
        context.run_migrations()


async def online():
    engine = create_async_engine(settings.database_url)
    try:
        async with engine.connect() as connection:
            await connection.run_sync(migrate)
    finally:
        await engine.dispose()


if context.is_offline_mode():
    context.configure(url=settings.database_url, literal_binds=True, target_metadata=Base.metadata)
    with context.begin_transaction():
        context.run_migrations()
else:
    asyncio.run(online())
