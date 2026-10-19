---
name: alpine-dev
description: Install and use Python, Node, git or other Linux tools in the built-in Alpine terminal.
---
Only with the Alpine backend (run_command says "Alpine Linux shell").
- Update once: `apk update`
- Python: `apk add python3 py3-pip`, then `python3 script.py`. Pip packages: `pip install --break-system-packages <pkg>`.
- Node: `apk add nodejs npm`. Git: `apk add git`. Build tools: `apk add build-base`.
- Search packages: `apk search <name>`.
- The workspace is /workspace (the user's IRISLocalAI folder). Keep files there.
- Long installs may hit the 120 s limit; split them into separate commands.
- If a command is not found, install its package first and retry once.
