import uvicorn

from .config import Settings


def main():
    settings = Settings()
    uvicorn.run(
        "pocketdisco.app:create_app",
        factory=True,
        workers=1,
        host=settings.bind_host,
        port=settings.port,
        log_level="warning",
        access_log=False,
        forwarded_allow_ips=settings.trusted_proxy_ips,
        ws_max_size=8192,
        limit_concurrency=settings.concurrency_limit,
        timeout_graceful_shutdown=settings.shutdown_seconds,
    )


if __name__ == "__main__":
    main()
