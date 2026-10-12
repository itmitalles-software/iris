#!/usr/bin/env bash
# Set up a Hermes Agent host so the Apollo Android app can connect to it.
#
# What it does (Linux, systemd user session):
#   1. creates a dashboard login (username + scrypt password hash + token secret),
#   2. stores it with the public URL in a private env file (config.yaml stays untouched),
#   3. installs a systemd user service running `hermes serve` on 127.0.0.1 only,
#   4. checks status, host guard and login, and prints the one Tailscale command left.
#
# Run it again at any time: it updates the service and keeps an existing login
# unless you pass --new-login. `--uninstall` removes everything it created.
#
# Usage: scripts/server/setup-hermes-for-apollo.sh [options]
#   --user NAME            login name (default: $USER)
#   --password-stdin       read the password from stdin instead of prompting
#   --generate-password    create a random password and save it to the login file
#   --new-login            replace an existing login
#   --public-url URL       HTTPS origin the phone uses (default: https://<tailscale name>)
#   --port PORT            loopback port for hermes serve (default: 9119)
#   --service-name NAME    systemd user unit name (default: hermes-serve)
#   --config-dir DIR       where the env/login files go (default: ~/.config/apollo)
#   --no-service           only write the env file and print how to run hermes serve
#   --uninstall            stop and remove the service and the files it created
#   -h, --help             show this help
set -euo pipefail

LOGIN_USER="${USER:-hermes}"
PASSWORD_MODE="prompt"
NEW_LOGIN=0
PUBLIC_URL=""
PORT=9119
SERVICE_NAME="hermes-serve"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/apollo"
INSTALL_SERVICE=1
UNINSTALL=0

usage() { sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }
info() { printf '==> %s\n' "$*"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --user) LOGIN_USER="${2:?}"; shift 2 ;;
        --password-stdin) PASSWORD_MODE="stdin"; shift ;;
        --generate-password) PASSWORD_MODE="generate"; shift ;;
        --new-login) NEW_LOGIN=1; shift ;;
        --public-url) PUBLIC_URL="${2:?}"; shift 2 ;;
        --port) PORT="${2:?}"; shift 2 ;;
        --service-name) SERVICE_NAME="${2:?}"; shift 2 ;;
        --config-dir) CONFIG_DIR="${2:?}"; shift 2 ;;
        --no-service) INSTALL_SERVICE=0; shift ;;
        --uninstall) UNINSTALL=1; shift ;;
        -h | --help) usage; exit 0 ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done

case "$PORT" in '' | *[!0-9]*) die "--port must be a number" ;; esac
case "$SERVICE_NAME" in '' | *[!A-Za-z0-9_.@-]*) die "--service-name may only contain letters, digits and _.@-" ;; esac

ENV_FILE="$CONFIG_DIR/$SERVICE_NAME.env"
LOGIN_FILE="$CONFIG_DIR/$SERVICE_NAME-login.txt"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_FILE="$UNIT_DIR/$SERVICE_NAME.service"

if [ "$UNINSTALL" = 1 ]; then
    if command -v systemctl >/dev/null && [ -f "$UNIT_FILE" ]; then
        systemctl --user disable --now "$SERVICE_NAME.service" >/dev/null 2>&1 || true
        rm -f "$UNIT_FILE"
        systemctl --user daemon-reload
        info "Removed service $SERVICE_NAME"
    fi
    rm -f "$ENV_FILE" "$LOGIN_FILE"
    rmdir "$CONFIG_DIR" 2>/dev/null || true
    info "Removed $ENV_FILE and $LOGIN_FILE"
    echo "If you published the port with Tailscale Serve, also run: sudo tailscale serve --https=443 off"
    exit 0
fi

# --- prerequisites -----------------------------------------------------------
HERMES_BIN="$(command -v hermes || true)"
[ -n "$HERMES_BIN" ] || die "the 'hermes' command was not found. Install Hermes Agent first."
command -v python3 >/dev/null || die "python3 is required to hash the password"
command -v curl >/dev/null || die "curl is required for the final check"
if [ "$INSTALL_SERVICE" = 1 ]; then
    command -v systemctl >/dev/null || die "systemd not found; rerun with --no-service"
    systemctl --user show-environment >/dev/null 2>&1 || die "no systemd user session; rerun with --no-service"
fi

if [ -z "$PUBLIC_URL" ]; then
    if command -v tailscale >/dev/null; then
        TS_NAME="$(tailscale status --json 2>/dev/null |
            python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)"
    fi
    [ -n "${TS_NAME:-}" ] || die "could not read the Tailscale name; pass --public-url https://<host>"
    PUBLIC_URL="https://$TS_NAME"
fi
case "$PUBLIC_URL" in https://*) ;; *) die "--public-url must start with https://" ;; esac
case "$PUBLIC_URL$LOGIN_USER" in *"'"*) die "quotes are not allowed in --public-url or --user" ;; esac
PUBLIC_HOST="${PUBLIC_URL#https://}"
PUBLIC_HOST="${PUBLIC_HOST%%/*}"

# --- login -------------------------------------------------------------------
umask 077
mkdir -p "$CONFIG_DIR"
chmod 700 "$CONFIG_DIR"

PASSWORD=""
if [ -f "$ENV_FILE" ] && [ "$NEW_LOGIN" = 0 ]; then
    info "Keeping the existing login in $ENV_FILE (use --new-login to replace it)"
    # Only the URL/port may change on a rerun; credentials stay as they are.
    sed -i "s|^HERMES_DASHBOARD_PUBLIC_URL=.*|HERMES_DASHBOARD_PUBLIC_URL='$PUBLIC_URL'|" "$ENV_FILE"
    LOGIN_USER="$(sed -n "s/^HERMES_DASHBOARD_BASIC_AUTH_USERNAME='\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$ENV_FILE")"
else
    case "$PASSWORD_MODE" in
        stdin) IFS= read -r PASSWORD ;;
        generate) PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')" ;;
        prompt)
            read -r -s -p "Password for Apollo login '$LOGIN_USER': " PASSWORD; echo
            read -r -s -p "Repeat password: " PASSWORD2; echo
            [ "$PASSWORD" = "$PASSWORD2" ] || die "passwords do not match"
            ;;
    esac
    [ "${#PASSWORD}" -ge 12 ] || die "use at least 12 characters"

    # Same format Hermes uses (plugins/dashboard_auth/basic: scrypt n=2^14 r=8 p=1, 16-byte salt).
    CREDENTIALS="$(PASSWORD="$PASSWORD" python3 - <<'PY'
import base64, hashlib, os, secrets
pw = os.environ["PASSWORD"].encode()
salt = secrets.token_bytes(16)
dk = hashlib.scrypt(pw, salt=salt, n=2**14, r=8, p=1, dklen=32, maxmem=0)
print(f"scrypt${2**14}$8$1${base64.b64encode(salt).decode()}${base64.b64encode(dk).decode()}")
print(base64.b64encode(secrets.token_bytes(32)).decode())
PY
)"
    PASSWORD_HASH="$(printf '%s\n' "$CREDENTIALS" | sed -n 1p)"
    TOKEN_SECRET="$(printf '%s\n' "$CREDENTIALS" | sed -n 2p)"
    {
        echo "# Hermes dashboard login for Apollo. Written by setup-hermes-for-apollo.sh."
        echo "# Loaded only by the $SERVICE_NAME user service; config.yaml stays untouched."
        echo "# Values are single-quoted: the hash contains '\$' and must not be expanded."
        echo "HERMES_DASHBOARD_PUBLIC_URL='$PUBLIC_URL'"
        echo "HERMES_DASHBOARD_BASIC_AUTH_USERNAME='$LOGIN_USER'"
        echo "HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH='$PASSWORD_HASH'"
        echo "HERMES_DASHBOARD_BASIC_AUTH_SECRET='$TOKEN_SECRET'"
    } >"$ENV_FILE"
    chmod 600 "$ENV_FILE"
    info "Wrote $ENV_FILE"
    if [ "$PASSWORD_MODE" = generate ]; then
        printf 'Apollo login for Hermes\nServer URL: %s\nUsername:   %s\nPassword:   %s\n' \
            "$PUBLIC_URL" "$LOGIN_USER" "$PASSWORD" >"$LOGIN_FILE"
        chmod 600 "$LOGIN_FILE"
        info "Saved the generated password to $LOGIN_FILE"
    fi
fi

# --- service -----------------------------------------------------------------
SERVE_CMD=("$HERMES_BIN" serve --host 127.0.0.1 --port "$PORT" --skip-build)
if [ "$INSTALL_SERVICE" = 0 ]; then
    echo
    echo "Start Hermes with this login (keep it running, e.g. in tmux):"
    echo "  set -a; . '$ENV_FILE'; set +a; ${SERVE_CMD[*]}"
else
    mkdir -p "$UNIT_DIR"
    {
        echo "# Hermes backend for the Apollo Android app. Written by setup-hermes-for-apollo.sh."
        echo "[Unit]"
        echo "Description=Hermes Agent backend for Apollo (127.0.0.1:$PORT)"
        echo "After=network-online.target"
        echo "Wants=network-online.target"
        echo
        echo "[Service]"
        echo "Type=simple"
        printf 'ExecStart='; printf '"%s" ' "${SERVE_CMD[@]}"; echo
        echo "Environment=\"PATH=$PATH\""
        [ -n "${HERMES_HOME:-}" ] && echo "Environment=\"HERMES_HOME=$HERMES_HOME\""
        echo "EnvironmentFile=$ENV_FILE"
        echo "Restart=on-failure"
        echo "RestartSec=5"
        echo
        echo "[Install]"
        echo "WantedBy=default.target"
    } >"$UNIT_FILE"
    systemctl --user daemon-reload
    systemctl --user enable "$SERVICE_NAME.service" >/dev/null 2>&1
    systemctl --user restart "$SERVICE_NAME.service"
    info "Service $SERVICE_NAME is enabled and (re)started"
fi

# --- verification ------------------------------------------------------------
if [ "$INSTALL_SERVICE" = 1 ]; then
    info "Waiting for hermes serve on 127.0.0.1:$PORT"
    for _ in $(seq 1 60); do
        curl -fsS -o /dev/null "http://127.0.0.1:$PORT/api/status" -H "Host: $PUBLIC_HOST" 2>/dev/null && break
        sleep 1
    done
    STATUS="$(curl -fsS "http://127.0.0.1:$PORT/api/status" -H "Host: $PUBLIC_HOST")" ||
        die "hermes serve did not answer; see: journalctl --user -u $SERVICE_NAME -n 50"
    printf '%s' "$STATUS" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("auth_required") else 1)' ||
        die "login is not enforced; check $ENV_FILE"
    BAD_HOST="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/status" -H 'Host: not-your-host.invalid')"
    [ "$BAD_HOST" = 400 ] || die "unexpected answer for a foreign host header: $BAD_HOST"
    if [ -n "$PASSWORD" ]; then
        BODY="$(LOGIN_USER="$LOGIN_USER" PASSWORD="$PASSWORD" python3 -c 'import json,os; print(json.dumps({"provider": "basic", "username": os.environ["LOGIN_USER"], "password": os.environ["PASSWORD"]}))')"
        CODE="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/auth/password-login" \
            -H "Host: $PUBLIC_HOST" -H 'Content-Type: application/json' --data-binary @- <<<"$BODY")"
        [ "$CODE" = 200 ] || die "login test failed (HTTP $CODE)"
        info "Login test passed"
    fi
    info "Checks passed: login enforced, foreign hosts rejected"
fi

TS_CMD="tailscale serve --bg $PORT"
if command -v tailscale >/dev/null && tailscale serve status 2>/dev/null | grep -q "127.0.0.1:$PORT"; then
    info "Tailscale Serve already publishes port $PORT"
else
    echo
    echo "Last step: publish Hermes inside your tailnet over HTTPS:"
    echo "  sudo $TS_CMD"
    echo "(no sudo needed if you once ran: sudo tailscale set --operator=\$USER)"
fi
echo
echo "In Apollo: Sign in -> $PUBLIC_URL -> user '$LOGIN_USER' and your password."
