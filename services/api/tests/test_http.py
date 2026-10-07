import pytest
from fastapi.testclient import TestClient

from pocketdisco.app import create_app


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings)) as value:
        yield value


def guest(client, name="Mira"):
    result = client.post("/v1/auth/guest", json={"display_name": name})
    assert result.status_code == 201
    session = result.json()
    return session, {"Authorization": f"Bearer {session['access_token']}"}


def test_http_guest_room_join_leave(client):
    first, host_headers = guest(client)
    second, friend_headers = guest(client, "Sam")
    created = client.post("/v1/rooms", json={"name": "Night drive"}, headers=host_headers)
    assert created.status_code == 201
    assert created.headers["Cache-Control"] == "no-store"
    room = created.json()
    joined = client.post(f"/v1/rooms/{room['invite_code']}/join", json={}, headers=friend_headers)
    assert joined.status_code == 200
    assert len(joined.json()["snapshot"]["members"]) == 2
    room_id = room["snapshot"]["room_id"]
    left = client.post(f"/v1/rooms/{room_id}/leave", json={}, headers=host_headers)
    assert left.json() == {"ok": True}
    snapshot = client.get(f"/v1/rooms/{room_id}/snapshot", headers=friend_headers).json()
    assert snapshot["host_id"] == second["user"]["id"]


def test_rest_room_endpoints_require_auth(client):
    result = client.post("/v1/rooms", json={"name": "Room"})
    assert result.status_code == 401
    assert result.json()["detail"]["code"] == "unauthorized"


@pytest.mark.parametrize("name", ["", "   ", "a" * 41, "bad\x00name", 13])
def test_guest_name_validation_does_not_echo_input(client, name):
    response = client.post("/v1/auth/guest", json={"display_name": name})
    assert response.status_code == 422
    assert response.json() == {
        "detail": {"code": "invalid_request", "message": "Check the supplied fields."}
    }


def test_body_is_bounded(client):
    response = client.post("/v1/auth/guest", content=b"x" * 8193)
    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "request_too_large"


def test_refresh_rotation_through_http(client):
    first, headers = guest(client)
    second = client.post("/v1/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert second.status_code == 200
    assert second.json()["refresh_token"] != first["refresh_token"]
    reused = client.post("/v1/auth/refresh", json={"refresh_token": first["refresh_token"]})
    assert reused.status_code == 401
    assert client.post("/v1/rooms", headers=headers, json={"name": "Room"}).status_code == 401


def test_ticket_requires_membership(client):
    first, headers = guest(client)
    second, outsider = guest(client, "Sam")
    room_id = client.post("/v1/rooms", json={"name": "Room"}, headers=headers).json()["snapshot"][
        "room_id"
    ]
    result = client.post("/v1/realtime/tickets", json={"room_id": room_id}, headers=outsider)
    assert result.status_code == 403


def test_guest_rate_limit(client):
    for index in range(30):
        guest(client, str(index))
    result = client.post("/v1/auth/guest", json={"display_name": "One more"})
    assert result.status_code == 429


def test_health_marks_local_test_mode(client):
    assert client.get("/healthz").json() == {"status": "ok", "mode": "local_test"}
