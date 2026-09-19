"""Exercise the running Docker Compose stack using only Python's standard library."""
import json
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"

def call(method, path, payload=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
                                     data=json.dumps(payload).encode() if payload is not None else None)
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)

status, a = call("POST", "/api/accounts", {"ownerName": "Alice", "startingBalance": 100})
assert status == 201
status, b = call("POST", "/api/accounts", {"ownerName": "Bob", "startingBalance": 0})
assert status == 201
payload = {"fromAccountId": a["id"], "toAccountId": b["id"], "amount": 25}
key = str(uuid.uuid4())
status, first = call("POST", "/api/transfers", payload, key)
assert status == 201, first
status, replay = call("POST", "/api/transfers", payload, key)
assert status == 200 and replay == first
status, error = call("POST", "/api/transfers", {**payload, "amount": 1000}, str(uuid.uuid4()))
assert status == 422 and error["code"] == "INSUFFICIENT_FUNDS"
assert call("GET", "/api/accounts/" + a["id"])[1]["balance"] == 75
assert call("GET", "/api/accounts/" + b["id"])[1]["balance"] == 25
assert len(call("GET", "/api/accounts/" + a["id"] + "/transactions")[1]) == 1
assert "/api/transfers" in call("GET", "/v3/api-docs")[1]["paths"]
print("PASS: Docker HTTP smoke test (accounts, transfer, replay, rejection, balances, history, OpenAPI)")
