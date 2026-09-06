---
name: run-server
description: Build, run, and drive the LedgerPrime FastAPI cloud-sync server (Python). Use when asked to start the server, run its tests, or confirm an API change works - register a user, get a JWT, call an endpoint.
---

The LedgerPrime server is a FastAPI app (SQLAlchemy async, Alembic migrations) that receives
what the Android app's Outbox pushes once a device opts into Cloud Sync. It has no separate
"driver" binary - drive it by starting `uvicorn` and hitting it with `curl`, as
`.claude/skills/run-server/smoke.sh` does. All paths below are relative to `server/`.

## Prerequisites

- Python 3.14 at a real path, e.g. `C:\Python314\python.exe` - **not** the bare `python`/
  `python3` on PATH, which on this machine are Microsoft Store execution-alias stubs that
  fail immediately (`Python was not found; run without arguments to install...`). Use
  `C:/Python314/python.exe -m venv .venv` or the `py -3` launcher.
- No Docker/Postgres needed for local dev or tests - `app/config.py`'s default
  `DATABASE_URL` is `sqlite+aiosqlite:///./ledgerprime_dev.db`.

## Build

```bash
"C:/Python314/python.exe" -m venv .venv
.venv/Scripts/python.exe -m pip install -q -r requirements/base.txt -r requirements/dev.txt
.venv/Scripts/python.exe -m alembic upgrade head    # creates ledgerprime_dev.db
```

If you find a `.venv/` already checked out from a different machine, delete and recreate it -
its `pyvenv.cfg` hardcodes an absolute interpreter path from wherever it was first created and
will fail with `did not find executable at 'C:\Users\<other-user>\...\python.exe'`.

## Run (agent path)

```bash
bash .claude/skills/run-server/smoke.sh
```

This starts `uvicorn` on `127.0.0.1:8000` against a fresh migrated SQLite DB, then drives a
real flow with `curl`: health check -> `POST /api/v1/auth/register` (returns a JWT) ->
an authenticated `GET /api/v1/reports/trial-balance` call with that JWT - and tears the
server down on exit. Server log lands at `/tmp/ledgerprime-server-smoke.log`.

To drive it by hand instead:

```bash
.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 &
curl -s http://127.0.0.1:8000/api/v1/health
curl -s -X POST http://127.0.0.1:8000/api/v1/auth/register -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"TestPass123!"}'
# -> {"accessToken": "...", "refreshToken": "...", ...}
# All report endpoints take camelCase query params, e.g.:
curl -s -H "Authorization: Bearer <accessToken>" \
  "http://127.0.0.1:8000/api/v1/reports/trial-balance?companyId=x&financialYearId=y"
```

Full route list: `curl -s http://127.0.0.1:8000/openapi.json | python -c "import json,sys; print(list(json.load(sys.stdin)['paths']))"`.

## Run (human path)

Same as above - there is no GUI. `Ctrl-C` (or kill the uvicorn process) to stop.

## Test

```bash
.venv/Scripts/python.exe -m pytest -q
```

115 tests pass (as of this session).

---

## Gotchas

- **Migration `0005_management_layer.py` had a duplicate-index bug** - it declared
  `index=True` on `company_subscriptions.company_id`/`financial_year_id` and
  `bank_upi_profiles.company_id`/`party_id` (which auto-creates `ix_<table>_<col>`), *and*
  separately called `op.create_index("ix_<table>_<col>", ...)` for the same names, so
  `alembic upgrade head` failed with `index ix_company_subscriptions_company_id already
  exists`. Every other migration in this repo (0001-0004) only ever uses `index=True`. Fixed
  in this session by deleting the four redundant explicit `op.create_index` calls to match the
  established convention - if you see this error again, check nothing re-added them.
- **`python`/`python3` on PATH are Windows Store aliases, not real interpreters** on this
  machine - they exit immediately with a store-redirect message instead of running anything.
  Always invoke the venv's own `.venv/Scripts/python.exe`, or `C:/Python314/python.exe` /
  `py -3` to create it.
- **A stray `ledgerprime_dev.db` left over from a previous run can hold a file lock** if a
  prior `uvicorn` process wasn't actually killed (on Windows, `pkill -f uvicorn` from Git Bash
  does **not** match the `python.exe` process - use `taskkill //F //IM python.exe` or target
  the PID from `Started server process [PID]` in the log). `smoke.sh` assumes the DB file is
  free to delete; if `rm` fails with "Device or resource busy," kill the old process first.
