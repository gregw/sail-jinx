#!/usr/bin/env bash
# install.sh — installs the sail-jinx systemd service on a Debian/Raspberry Pi system.
# Must be run as root (or with sudo).
#
# Safe to re-run: upgrading is `git pull && sudo etc/install.sh`. The data directory
# is never overwritten — config files are seeded only if missing, and the store is
# left alone entirely.
set -euo pipefail

SERVICE_USER=sail-jinx
INSTALL_DIR=/opt/sail-jinx
DATA_DIR=/var/lib/sail-jinx
SERVICE_FILE=/etc/systemd/system/sail-jinx.service
SRC_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# ---- Verify prerequisites ----
for cmd in java mvn rsync; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: '$cmd' not found. Install it before running this script."
        exit 1
    fi
done

echo "==> Creating system user '$SERVICE_USER' (if not already present)…"
if ! id "$SERVICE_USER" &>/dev/null; then
    useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi

echo "==> Installing project to $INSTALL_DIR…"
mkdir -p "$INSTALL_DIR"
# The source tree only. `data` is excluded deliberately: the live config and the
# store live in $DATA_DIR, and --delete here would take them with it.
rsync -a --delete \
    --exclude='.git' --exclude='data' --exclude='target' --exclude='wiki' \
    "$SRC_DIR/" "$INSTALL_DIR/"
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

echo "==> Creating data directory $DATA_DIR…"
mkdir -p "$DATA_DIR/config" "$DATA_DIR/store"

# Seed config, never overwrite it. An upgrade must not silently revert the club's
# settings, its learned boat aliases, or its OAuth client.
for f in config.yaml aliases.yaml design.yaml; do
    if [ -f "$SRC_DIR/data/config/$f" ] && [ ! -f "$DATA_DIR/config/$f" ]; then
        echo "    seeding config/$f"
        install -m 644 "$SRC_DIR/data/config/$f" "$DATA_DIR/config/$f"
    fi
done
if [ ! -f "$DATA_DIR/config/auth.yaml" ]; then
    echo "    seeding config/auth.yaml.example (authentication is OFF until you edit it)"
    install -m 640 "$SRC_DIR/data/config/auth.yaml.example" \
        "$DATA_DIR/config/auth.yaml.example"
fi

chown -R "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR"
# auth.yaml holds an OAuth client secret; nobody but the service needs to read it.
if [ -f "$DATA_DIR/config/auth.yaml" ]; then
    chmod 600 "$DATA_DIR/config/auth.yaml"
fi

echo "==> Pre-building the project…"
sudo -u "$SERVICE_USER" \
    HOME="$DATA_DIR" \
    sh -c "cd '$INSTALL_DIR' && mvn --batch-mode \
        -Dmaven.repo.local='$DATA_DIR/.m2/repository' \
        compile -q"

echo "==> Installing systemd service unit…"
install -m 644 "$SRC_DIR/etc/sail-jinx.service" "$SERVICE_FILE"

echo "==> Reloading systemd and enabling service…"
systemctl daemon-reload
systemctl enable sail-jinx.service

echo ""
echo "Installation complete."
echo ""
echo "  Start:   sudo systemctl start sail-jinx"
echo "  Stop:    sudo systemctl stop sail-jinx"
echo "  Status:  sudo systemctl status sail-jinx"
echo "  Logs:    sudo journalctl -u sail-jinx -f"
echo ""
echo "Data directory: $DATA_DIR"
echo "  config/   club settings, boat aliases, and (optionally) auth.yaml"
echo "  store/    the ONLY copy of the club's race data — back it up"
echo ""
if [ ! -f "$DATA_DIR/config/auth.yaml" ]; then
    echo "Authentication is OFF: every connection is treated as an administrator."
    echo "This server is reachable over the network, so set it up before real use:"
    echo ""
    echo "  sudo cp $DATA_DIR/config/auth.yaml.example $DATA_DIR/config/auth.yaml"
    echo "  sudo chown $SERVICE_USER:$SERVICE_USER $DATA_DIR/config/auth.yaml"
    echo "  sudo chmod 600 $DATA_DIR/config/auth.yaml"
    echo "  sudo -e $DATA_DIR/config/auth.yaml     # fill in the Google client, enabled: true"
    echo "  sudo systemctl restart sail-jinx"
    echo ""
fi
