import uvicorn

if __name__ == "__main__":
    uvicorn.run(
        "pocketdisco.app:create_app",
        factory=True,
        host="127.0.0.1",
        port=8000,
        log_level="warning",
        access_log=False,
        forwarded_allow_ips="",
        ws_max_size=8192,
    )
