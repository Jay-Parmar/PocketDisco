from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from pocketdisco.app import create_app


@pytest.fixture
def setup(settings):
    with TestClient(create_app(settings)) as client:
        host = client.post("/v1/auth/guest", json={"display_name": "Mira"}).json()
        headers = {"Authorization": f"Bearer {host['access_token']}"}
        created = client.post("/v1/rooms", json={"name": "Room"}, headers=headers).json()
        yield client, headers, host, created


def ticket(client, headers, room_id):
    response = client.post("/v1/realtime/tickets", json={"room_id": room_id}, headers=headers)
    assert response.status_code == 200
    return response.json()["ticket"]


def command(kind, payload=None, command_id=None):
    return {
        "v": 1,
        "type": kind,
        "command_id": command_id or str(uuid4()),
        "payload": payload or {},
    }


def receive_type(socket, kind):
    for _ in range(10):
        event = socket.receive_json()
        if event["type"] == kind:
            return event
    pytest.fail(f"Missing {kind} event")


def test_websocket_hello_snapshot_and_one_use_ticket(setup):
    client, headers, host, created = setup
    room_id = created["snapshot"]["room_id"]
    value = ticket(client, headers, room_id)
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        hello = socket.receive_json()
        assert hello["type"] == "hello"
        assert hello["payload"]["heartbeat_interval_ms"] == 15000
        snapshot = socket.receive_json()
        assert snapshot["type"] == "room.snapshot"
        assert snapshot["payload"]["members"][0]["connected"]
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect(f"/v1/realtime?ticket={value}"):
                pytest.fail("Ticket was reused")


def test_two_clients_see_chat_and_readiness(setup):
    client, headers, host, created = setup
    friend = client.post("/v1/auth/guest", json={"display_name": "Sam"}).json()
    friend_headers = {"Authorization": f"Bearer {friend['access_token']}"}
    client.post(f"/v1/rooms/{created['invite_code']}/join", headers=friend_headers, json={})
    room_id = created["snapshot"]["room_id"]
    first_ticket = ticket(client, headers, room_id)
    second_ticket = ticket(client, friend_headers, room_id)
    with client.websocket_connect(f"/v1/realtime?ticket={first_ticket}") as first:
        receive_type(first, "room.snapshot")
        with client.websocket_connect(f"/v1/realtime?ticket={second_ticket}") as second:
            receive_type(second, "room.snapshot")
            ready = command("member.ready", {"ready": True})
            first.send_json(ready)
            assert (
                receive_type(first, "command.ack")["payload"]["command_id"] == ready["command_id"]
            )
            updated = receive_type(second, "room.snapshot")
            assert next(m for m in updated["payload"]["members"] if m["role"] == "host")["ready"]
            chat = command("chat.send", {"body": " Hello everyone "})
            first.send_json(chat)
            assert receive_type(first, "command.ack")["payload"]["command_id"] == chat["command_id"]
            changed = receive_type(second, "room.snapshot")
            assert changed["payload"]["messages"][-1]["body"] == "Hello everyone"
            first.send_json(chat)
            receive_type(first, "command.ack")
            current = client.get(f"/v1/rooms/{room_id}/snapshot", headers=headers).json()
            assert len(current["messages"]) == 1


def test_ping_and_snapshot_recovery(setup):
    client, headers, host, created = setup
    room_id = created["snapshot"]["room_id"]
    value = ticket(client, headers, room_id)
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        socket.send_json(command("ping", {"client_time_ms": 1234}))
        pong = receive_type(socket, "pong")
        assert pong["payload"] == {"client_time_ms": 1234}
        assert pong["server_time_ms"] > 0
        socket.send_json(command("sync.request"))
        assert receive_type(socket, "room.snapshot")["room_id"] == room_id


def test_invalid_chat_has_correlated_error_and_no_ack(setup):
    client, headers, host, created = setup
    value = ticket(client, headers, created["snapshot"]["room_id"])
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        invalid = command("chat.send", {"body": "   "})
        socket.send_json(invalid)
        error = receive_type(socket, "error")
        assert error["payload"]["code"] == "invalid_command"
        assert error["payload"]["command_id"] == invalid["command_id"]


def test_leave_closes_room_socket(setup):
    client, headers, host, created = setup
    room_id = created["snapshot"]["room_id"]
    value = ticket(client, headers, room_id)
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        assert (
            client.post(f"/v1/rooms/{room_id}/leave", json={}, headers=headers).status_code == 200
        )
        receive_type(socket, "error")
        with pytest.raises(WebSocketDisconnect) as error:
            socket.receive_json()
        assert error.value.code == 4403


def test_revoked_session_cannot_use_issued_ticket(setup):
    client, headers, host, created = setup
    value = ticket(client, headers, created["snapshot"]["room_id"])
    for _ in range(2):
        client.post("/v1/auth/refresh", json={"refresh_token": host["refresh_token"]})
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect(f"/v1/realtime?ticket={value}"):
            pytest.fail("Revoked session used a ticket")


def test_oversized_socket_message_closes(setup):
    client, headers, host, created = setup
    value = ticket(client, headers, created["snapshot"]["room_id"])
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        socket.send_text("x" * 8193)
        with pytest.raises(WebSocketDisconnect) as error:
            socket.receive_json()
        assert error.value.code == 1009


def test_binary_socket_message_closes(setup):
    client, headers, host, created = setup
    value = ticket(client, headers, created["snapshot"]["room_id"])
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        socket.send_bytes(b"unsupported")
        with pytest.raises(WebSocketDisconnect) as error:
            socket.receive_json()
        assert error.value.code == 1003


@pytest.mark.parametrize("version", [True, 1.0, "1", 2])
def test_invalid_protocol_version_is_rejected(setup, version):
    client, headers, host, created = setup
    value = ticket(client, headers, created["snapshot"]["room_id"])
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        invalid = command("chat.send", {"body": "Bad version"})
        invalid["v"] = version
        socket.send_json(invalid)
        assert receive_type(socket, "error")["payload"]["code"] == "invalid_command"


def test_normal_disconnect_clears_presence_before_recovery(setup):
    client, headers, host, created = setup
    room_id = created["snapshot"]["room_id"]
    value = ticket(client, headers, room_id)
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        socket.send_json(command("member.ready", {"ready": True}))
        receive_type(socket, "command.ack")
    snapshot = client.get(f"/v1/rooms/{room_id}/snapshot", headers=headers).json()
    assert not snapshot["members"][0]["connected"]
    assert not snapshot["members"][0]["ready"]


def test_active_socket_rejects_revoked_session(setup):
    client, headers, host, created = setup
    value = ticket(client, headers, created["snapshot"]["room_id"])
    with client.websocket_connect(f"/v1/realtime?ticket={value}") as socket:
        receive_type(socket, "room.snapshot")
        for _ in range(2):
            client.post("/v1/auth/refresh", json={"refresh_token": host["refresh_token"]})
        outgoing = command("chat.send", {"body": "Should not arrive"})
        socket.send_json(outgoing)
        failure = receive_type(socket, "error")
        assert failure["payload"]["command_id"] == outgoing["command_id"]
        with pytest.raises(WebSocketDisconnect) as error:
            socket.receive_json()
        assert error.value.code == 4401
