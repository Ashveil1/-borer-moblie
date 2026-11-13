# Borer (Android)

An autonomous security-testing agent harness rewritten natively for Android in **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, and **OkHttp**.

## Features

- **Autonomous Agent Console**: Streaming OpenAI-compatible SSE completions (`/chat/completions` & `/responses`) with multi-provider fallback chains, model context window detection, turn-boundary compaction, no-progress nudging, and live tool-call inspection.
- **Full Built-in Tool Suite**:
  - Network & Protocols: `http` (custom method, headers, redirect mode, optional TLS verification toggle, timing breakdown, artifact spill), `net` (raw TCP / TLS sockets), and an in-process loopback `ChildProxyServer` (`HTTP_PROXY`/`HTTPS_PROXY`) capturing child shell/verify outbound traffic into `http.jsonl`.
  - Workspace & Execution: `bash`, `read`, `write`, `edit`, `ls`, `glob`, `grep`, and background `job` management, with selectable shell runtime (`Android /system/bin/sh`, `Termux proot-distro ubuntu` via `RUN_COMMAND`, or `Direct proot`).
  - Payload & Analysis: `transform` (URL, Base64, Hex, SHA/MD5 hashes, HMAC-SHA256, JWT decode, gzip/deflate, HTML entities, Caesar shift), `diff` (structural JSON/XML/HTML/text comparison with similarity score), `mutate` (deterministic payload variant generator with wrapper contexts).
  - Persistence & Reporting: `notes` (typed knowledge base stored in Room), `recall` (artifact paging, byte-slicing, and regex grep), `verify` (N-run reproducibility verifier), `report` (validated findings with full CVSS 3.1 vector scoring and Markdown export), `learn` (cross-session Bayesian memory `(success+1)/(attempts+2)`), `skill` (`SKILL.md` playbooks), `session` (fork/snapshot), `todo`, `scope`, and `finish`.
- **Sessions & Hunt Queue**: Browse, resume, fork, archive, or delete runs, inspect `events.jsonl` and `http.jsonl` audit trails, and run multi-target unattended hunt queues.
- **Intel Hub**: Inspect structured Notes, Artifacts, CVSS 3.1 Findings & Markdown Reports, `http.jsonl` network telemetry, and cross-session Bayesian Learning Memory.
- **Offline Security Lab**: Run `transform`, `mutate`, `diff`, and CVSS 3.1 calculations directly in the UI without needing a live LLM provider.

## Configuration

Configure providers (Base URL, Model, API Key, Chat/Responses API, Context Window, Reasoning Effort, Extra Headers), Ubuntu `proot-distro` runtime, harness parameters, and webhook/Telegram/Discord notifications directly inside **Menu → Settings**.

Default fallback credentials can also be pre-injected at build time from `.env` / `.env.example` via the **Secrets Gradle Plugin** into `BuildConfig` (`BORER_PROVIDER_1_*`, `BORER_PROVIDER_2_*`, `BORER_WEBHOOK_URL`, `BORER_TELEGRAM_*`, `BORER_DISCORD_WEBHOOK`).

## Build

- Language: Kotlin (AGP 9.1.1 built-in Kotlin)
- UI: Jetpack Compose + Material 3 + Navigation Compose (adaptive `NavigationBar` / `NavigationRail` via Window Size Classes)
- Persistence: Room (`RunDatabase` + `MemoryDatabase`) and app-private run directories (`filesDir/runs/<id>/`)
- Minimum SDK: 26, Compile/Target SDK: 36

## License

GPLv3. See `LICENSE`.
