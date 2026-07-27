# Docker Compose Debugging Notes — Intermittent Service Startup Failures

**Context:** 28 services in `docker-compose.yml`, ~2 services intermittently fail to come up on first `up -d` but succeed on rerun. Classic symptom of a **race condition at startup** — a dependency container is "running" but not actually *ready* when the dependent service starts.

---

## 1. Root Cause Checklist

- [ ] Do the flaky services have a `depends_on` pointing to a slow-starting dependency (DB, Kafka, config server, Eureka/Consul)?
- [ ] Does that dependency have a `healthcheck:` block?
- [ ] Is `depends_on` using `condition: service_healthy` (not just default `service_started`)?
- [ ] Does the app itself retry/backoff on failed connections, or fail fast on first attempt?
- [ ] Is the host under CPU/memory/disk pressure during the cold start of all 28 services?

### Fix: proper healthcheck-based dependency
```yaml
depends_on:
  db:
    condition: service_healthy

healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 5s
  timeout: 3s
  retries: 10
  start_period: 20s
```

---

## 2. Log Comparison (failed run vs successful rerun)

```bash
docker compose up -d
docker compose logs <service1> <service2> > first_run.log

# after rerun succeeds
docker compose logs <service1> <service2> > second_run.log

diff first_run.log second_run.log
```
Look for: connection refused, timeouts, DNS resolution failures, "waiting for X" messages present only in the failed run.

---

## 3. Container State / Exit Codes

```bash
docker compose ps -a
docker inspect <container_name> --format='{{.State.ExitCode}} {{.State.Error}}'
```
- Exit code `0` but unhealthy → app crashed cleanly after failed dependency check
- Non-zero exit → crash, check logs around that timestamp
- Restart loop / stuck "starting" → healthcheck or app retry timing out too early

---

## 4. Startup Timing / Event Order

```bash
docker events --filter 'event=start' --filter 'event=die' --filter 'event=health_status'
```
Shows exact ordering/timestamps of container start/die/health events — confirms whether service A starts before its real dependency B is actually ready.

Add timestamps for correlation across sessions (needs `moreutils`):
```bash
docker events | ts '%H:%M:%.S' > events.log
```

---

## 5. Resource Contention on Cold Start

```bash
docker stats --no-stream    # single snapshot
docker stats                # live, continuous
```
With 28 services starting simultaneously, CPU/memory/disk I/O spikes can cause slow services to time out on first boot but succeed on retry once other containers have settled.

---

## 6. Network / DNS Readiness

```bash
docker compose exec <service1> ping -c1 <dependency_service>
docker compose exec <service1> nslookup <dependency_service>
```
Checks whether internal Docker DNS resolution is lagging for a just-started dependency container.

---

## 7. Live Log Tail on Suspect Services

```bash
docker compose logs -f <service1> <service2>
```

---

## 8. Continuous Status Polling

```bash
watch -n1 'docker compose ps -a'
```

---

## Running Commands in Parallel (Remote/SSH)

These are mostly read-only/observational commands — safe to run concurrently across SSH sessions. **Only run `docker compose up -d` / `down` from one session at a time.**

### Recommended session layout

| Session | Purpose | Command | Start order |
|---|---|---|---|
| 1 | Trigger (the "conductor") | `docker compose down && docker compose up -d` | **Start last** |
| 2 | Live event stream | `docker events --filter 'event=start' --filter 'event=die' --filter 'event=health_status'` | Start first |
| 3 | Resource monitor | `docker stats` | Start first |
| 4 | Tail logs of suspects | `docker compose logs -f <service1> <service2>` | Start first |
| 5 | Poll container status | `watch -n1 'docker compose ps -a'` | Start first |

**Order of operations:**
1. Open sessions 2–5 first and get them actively watching.
2. Then in session 1, run `docker compose up -d`.
3. Let it fail naturally — **don't rerun immediately**. Inspect exit codes/logs on the dead containers first (container state via `docker inspect` is most useful *before* a restart clears it; `docker compose logs` persists regardless).

### Practical tips
- Prefer `tmux` (or `screen`) over multiple raw SSH windows — survives SSH disconnects, and panes can be tiled to watch everything at once:
  ```bash
  tmux new -s debug
  # split panes: Ctrl+b %  (vertical)  |  Ctrl+b "  (horizontal)
  ```
- `docker events` and `docker stats` are lightweight — safe to run alongside everything else, no lock contention.
- Redirect each session's output to a timestamped file so you can correlate timing across sessions after the fact.
