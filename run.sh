#!/usr/bin/env bash
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
. .venv/bin/activate && pip install -q -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload --reload-dir app
