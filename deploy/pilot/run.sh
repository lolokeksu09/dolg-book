#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

book_host=raschet.135-106-227-179.sslip.io
login_host=login-raschet.135-106-227-179.sslip.io
site_host=135-106-227-179.sslip.io
public_ip=135.106.227.179
caddy=ventry-web-1
source_file=/opt/ventry/deploy/Caddyfile
state=/opt/raschet-pilot/state
here=$(cd "$(dirname "$0")" && pwd)
action=${1:-start}
mode_marker='# raschet-pilot routes'
die() { printf '%s\n' "$*" >&2; exit 1; }

[[ $EUID == 0 ]] || die "Run as root on the VDS."
for program in docker curl python3 getent flock; do command -v "$program" >/dev/null || die "Missing: $program"; done
docker compose version >/dev/null
[[ $(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$caddy") == /opt/ventry ]] || die "Unexpected Ventry Compose directory."
mount=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/etc/caddy/Caddyfile"}}{{.Source}}{{end}}{{end}}' "$caddy")
[[ "$mount" == "$source_file" && -f "$source_file" ]] || die "Unexpected Caddyfile mount."
cmp -s "$source_file" <(docker exec "$caddy" cat /etc/caddy/Caddyfile) || die "Caddy mount and host file differ. Inspect Ventry before continuing."
export CADDY_IP
CADDY_IP=$(docker inspect -f '{{with index .NetworkSettings.Networks "ventry_default"}}{{.IPAddress}}{{end}}' "$caddy")
[[ "$CADDY_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Caddy is not on ventry_default."
export PILOT_STATE=$state
docker network inspect ventry_default >/dev/null
install -d -m 0700 "$state"
exec 9>"$state/run.lock"
flock -n 9 || die "Another pilot operation is active."
compose=(docker compose --env-file "$state/pilot.env" -f "$here/compose.yaml")
backup="$state/caddy.before.$(date -u +%Y%m%dT%H%M%SZ).$$"
candidate=$(mktemp "$state/caddy.candidate.XXXXXX")
remote="/tmp/raschet-caddy-$$"
reload_caddy() { docker exec "$caddy" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile; }
validate() {
    docker exec -i "$caddy" sh -c 'cat > "$1"' sh "$remote" < "$candidate"
    docker exec "$caddy" caddy validate --config "$remote" --adapter caddyfile
}
remove_routes() {
    python3 - "$source_file" "$candidate" "$mode_marker" <<'PY'
import pathlib, sys
source, output, marker = sys.argv[1:]
contents = pathlib.Path(source).read_text()
start, end = marker + " begin", marker + " end"
if contents.count(start) != contents.count(end) or contents.count(start) > 1:
    sys.exit("Unexpected pilot route markers")
if start in contents:
    before, remainder = contents.split(start)
    _, after = remainder.split(end)
    contents = before.rstrip() + "\n" + after.lstrip("\n")
pathlib.Path(output).write_text(contents)
PY
}
applied=0
started=0
cleanup() {
    code=$?
    trap - EXIT
    if (( code != 0 && applied )); then
        cat "$backup" > "$source_file"
        reload_caddy || printf '%s\n' "Restore Caddy manually from $backup" >&2
    fi
    if (( code != 0 && started )); then "${compose[@]}" stop || true; fi
    docker exec "$caddy" rm -f "$remote" >/dev/null 2>&1 || true
    rm -f "$candidate"
    exit "$code"
}
trap cleanup EXIT

case $action in
    start)
        for host in "$book_host" "$login_host"; do
            getent ahostsv4 "$host" | awk '{print $1}' | sort -u | grep -Fxq "$public_ip" || die "DNS for $host does not point to $public_ip."
        done
        baseline=$(curl -fsS --max-time 10 "https://$site_host/healthz")
        [[ "$baseline" == ok ]] || die "Ventry health check failed before deployment."
        python3 "$here/bootstrap.py" "$state" "$book_host" "$login_host"
        "${compose[@]}" config --quiet
        ;;
    stop)
        [[ -f "$state/pilot.env" ]] || die "Pilot state was not found."
        ;;
    *) die "Usage: bash deploy/pilot/run.sh {start|stop}" ;;
esac
cp "$source_file" "$backup"
remove_routes
if [[ $action == start ]]; then
    cat >> "$candidate" <<ROUTES

$mode_marker begin
$book_host {
    reverse_proxy raschet-api:3000
}
$login_host {
    @privatePaths path /admin /admin/* /health /health/* /metrics /metrics/*
    respond @privatePaths 404
    reverse_proxy raschet-id:8080
}
$mode_marker end
ROUTES
    if ! "${compose[@]}" ps --status running --services | grep -q .; then started=1; fi
    "${compose[@]}" up --build -d
fi
validate
cmp -s "$source_file" "$backup" || die "Caddyfile changed during setup. Retry after Ventry deployment finishes."
# Preserve the existing bind mount inode.
applied=1
cat "$candidate" > "$source_file"
reload_caddy

if [[ $action == start ]]; then
    verified=0
    for ((attempt=0; attempt<36; attempt++)); do
        if probe=$(curl -fsS --max-time 6 "https://$book_host/session" 2>/dev/null) &&
           python3 -c 'import json,sys; assert json.loads(sys.argv[1]) == {"enabled":True,"signedIn":False,"operator":False}' "$probe" &&
           metadata=$(curl -fsS --max-time 6 "https://$login_host/realms/raschet-pilot/.well-known/openid-configuration" 2>/dev/null) &&
           python3 -c 'import json,sys; assert json.loads(sys.argv[1])["issuer"] == sys.argv[2]' "$metadata" "https://$login_host/realms/raschet-pilot"; then
            verified=1
            break
        fi
        sleep 5
    done
    (( verified )) || die "Pilot HTTPS or discovery did not become ready. Check Docker logs and Caddy TLS logs."
    [[ $(curl -fsS --max-time 8 "https://$site_host/healthz") == ok ]] || die "Ventry health check failed after deployment."
    [[ $(curl -sS -o /dev/null -w '%{http_code}' --max-time 8 "https://$book_host/shops") == 401 ]] || die "Unexpected unauthenticated API status."
    printf 'PILOT_READY\nBook: https://%s/book/\nAdmin: https://%s/admin/\nAccounts: %s/accounts.txt\n' "$book_host" "$book_host" "$state"
else
    "${compose[@]}" stop
    printf 'PILOT_STOPPED\n'
fi
