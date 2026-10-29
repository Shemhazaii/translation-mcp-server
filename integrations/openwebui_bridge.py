"""
name: zip bridge
description: Mirror uploaded ZIP to /mnt/sugoi-files, call translateBatch, register the result ZIP as a chat attachment.
---
Installed in Open WebUI: Workspace > Functions ("zip bridge", active), then Activate + Global.
DB row id=zip_bridge; this file is the paste-ready content for that function.
NOTE: create it under Functions (Pipe/Filter), NOT under Tools — Tools creation calls
get_tool_specs() and returns 400 "Error creating tool" for a class Filter body.

This OWUI build executes Functions as a `Filter` class whose `inlet` hook runs
on every chat message. Plain `async def function(...)` is NOT accepted by this
version's loader (load_function_module_by_id requires Pipe/Filter/Action/Event).

What it does:
1. inlet: Resolves the attached file's raw on-disk path via __metadata__['files'] ->
   data/uploads/<id>_* (the raw file is intact; only RAG's extracted text is
   garbage for zips). Validates ZIP magic bytes (PK\x03\x04). Mirrors the zip to
   /mnt/sugoi-files/uploads (shared with the MCP server) and appends a message
   with the exact path so the model calls translateBatch with the real path.
2. outlet: Scans the final model response for /mnt/sugoi-files/results/*.zip,
   registers each result as a Files-DB row (so the attachment id resolves), and
   emits TWO things:
   a. a fenced JSON payload {"files":[{id, filename, content_type, size}]} —
      the contract consumed by the patched process_tool_result() (discussion
      #28614), which attaches these as native ChatGPT-style attachment cards;
   b. a markdown download link with ?attachment=true as fallback for unpatched
      UIs (anchor, not a tile — clicking downloads).
   Keep FileItem.svelte patched for authenticated-fetch clicks; with cookie
   auth the JSON block is enough for the card and the link works as-is.

Prerequisites:
- Open WebUI container mounts the shared dir: -v /mnt/sugoi-files:/mnt/sugoi-files
- MCP translation server reachable from the chat (tool enabled in chat settings).
- Link click auth: works if OWUI auth is cookie-based. With header-only JWT auth
  a plain navigation can 401; that needs the FileItem.svelte patch (authenticated
  fetch -> blob), see discussion #28614.

NOTE: adjust DB path below if this build's sqlite lives elsewhere than
/app/backend/data/webui.db.
"""

import json
import re
import shutil
import sqlite3
import time
import uuid
from pathlib import Path

DATA = Path('/app/backend/data/uploads')
DB = Path('/app/backend/data/webui.db')
SHARED = Path('/mnt/sugoi-files')
RESULTS = SHARED / 'results'
ZIP_MAGIC = b'PK\x03\x04'
RESULT_RE = re.compile(re.escape(str(RESULTS)) + r'/[^\s\)\]\'"]+\.zip')


def _register_file(user_id, fid, name, path):
    """Insert the result into the Files DB; returns the file id.

    This OWUI build is SQLAlchemy-based: `Files` exposes async
    `insert_new_file(user_id, FileForm)` only, so it is unusable from a sync
    Filter hook — direct sqlite is the reliable path. The live schema is
    (id, user_id, filename, meta, created_at, hash, data, updated_at, path);
    older builds used `name` instead of `filename`, so the insert is built from
    PRAGMA columns instead of a hardcoded statement.
    """
    size = path.stat().st_size
    meta = {'name': name, 'content_type': 'application/zip', 'size': size}
    data = {'size': size, 'status': 'completed'}
    now = int(time.time())
    conn = sqlite3.connect(str(DB))
    try:
        cols = [r[1] for r in conn.execute('PRAGMA table_info(file)')]
        if not cols:
            conn.execute('CREATE TABLE IF NOT EXISTS file '
                         '(id TEXT PRIMARY KEY, user_id TEXT, filename TEXT, meta JSON, '
                         'created_at BIGINT, hash TEXT, data JSON, updated_at BIGINT, path TEXT)')
            cols = [r[1] for r in conn.execute('PRAGMA table_info(file)')]
        row = {
            'id': fid, 'user_id': user_id, 'filename': name, 'name': name,
            'path': str(path), 'meta': json.dumps(meta), 'data': json.dumps(data),
            'hash': '', 'created_at': now, 'updated_at': now,
        }
        insert = [c for c in cols if c in row]
        conn.execute(
            'INSERT OR REPLACE INTO file (%s) VALUES (%s)' %
            (', '.join(insert), ', '.join('?' * len(insert))),
            tuple(row[c] for c in insert),
        )
        conn.commit()
    finally:
        conn.close()
    return fid


class Filter:
    def inlet(self, body, __metadata__):
        bridge_msgs = []
        for item in (__metadata__ or {}).get('files') or []:
            if not isinstance(item, dict) or item.get('type', 'file') != 'file':
                continue
            fid = str(item.get('id') or '')
            if not fid or fid.startswith(('http://', 'https://', 'data:')):
                continue
            hits = sorted(DATA.glob(f'{fid}*'))
            if not hits:
                continue
            p = hits[0]
            try:
                if p.open('rb').read(4) != ZIP_MAGIC:
                    continue
            except OSError:
                continue
            dest = p if str(p).startswith(str(SHARED)) else SHARED / 'uploads' / p.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            if dest != p:
                shutil.copyfile(p, dest)
            bridge_msgs.append({
                'role': 'user',
                'content': (
                    '[file bridge] Raw ZIP archive on disk (ignore its RAG-extracted text): '
                    + str(dest) + '. Call the MCP tool translateBatch with this exact filePath, '
                    'sourceLang=ja, targetLang=en, translator=offline, write the result ZIP to '
                    + str(SHARED) + '/results/, and report the result path.'
                ),
            })
        if bridge_msgs:
            # OWUI's RAG citation template locks the model into "answer from
            # context + cite [id]" mode, so it echoes the task prompt instead of
            # calling MCP tools. Override the system message on ZIP turns.
            for msg in body.get('messages') or []:
                if msg.get('role') == 'system':
                    msg['content'] = (msg.get('content') or '') + (
                        '\n[bridge override] For this turn, do not answer from the RAG '
                        'context and do not add citations. Call the MCP tool translateBatch '
                        'as instructed by the [file bridge] message, then report only the '
                        'result path.'
                    )
                    break
            body.setdefault('messages', []).extend(bridge_msgs)
        return body

    def outlet(self, user, model, params, response):
        user_id = (params.get('user') or user or {}).get('id') if isinstance(params, dict) else None
        if not user_id and isinstance(user, dict):
            user_id = user.get('id')
        entries = []
        for m in RESULT_RE.finditer(response or ''):
            src = Path(m.group(0))
            if not src.is_file():
                continue
            fid = str(uuid.uuid4())
            name = src.name
            dest = DATA / f'{fid}_{name}'
            try:
                shutil.copyfile(src, dest)
                _register_file(user_id, fid, name, dest)
                entries.append({
                    'id': fid,
                    'filename': name,
                    'content_type': 'application/zip',
                    'size': dest.stat().st_size,
                })
            except Exception:
                continue
        if entries:
            extra = [f'📦 download: [{e["filename"]}](/api/v1/files/{e["id"]}/content?attachment=true)'
                     for e in entries]
            # Structured payload consumed by the patched process_tool_result()
            # (discussion #28614): fenced JSON block, parsed out of the tool
            # result / outlet output, attached as native attachment cards.
            extra.append('```json\n' + json.dumps({'files': entries}) + '\n```')
            response = (response or '') + '\n\n' + '\n'.join(extra)
        return response
