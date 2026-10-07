class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status = status
        self.code = code
        self.message = message
        super().__init__(code)

    def detail(self):
        return {"code": self.code, "message": self.message}


def unauthorized():
    return ApiError(401, "unauthorized", "Your session has expired. Please sign in again.")
