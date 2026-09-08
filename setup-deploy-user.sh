#!/bin/bash
# Setup unprivileged deploy user for CI/CD
# Run ONCE on production server as root:
#   chmod +x setup-deploy-user.sh && ./setup-deploy-user.sh
set -e

DEPLOY_USER="deploy"
DEPLOY_DIR="/home/${DEPLOY_USER}/todo"
CURRENT_DIR="/root/todo"

echo "=== Creating deploy user ==="

# 1. Create user
if id "$DEPLOY_USER" &>/dev/null; then
    echo "User $DEPLOY_USER already exists, skipping creation"
else
    useradd -m -s /bin/bash "$DEPLOY_USER"
    echo "Created user: $DEPLOY_USER"
fi

# 2. Add to docker group
usermod -aG docker "$DEPLOY_USER"
echo "Added $DEPLOY_USER to docker group"

# 3. Setup SSH key (copy from root)
DEPLOY_SSH="/home/${DEPLOY_USER}/.ssh"
mkdir -p "$DEPLOY_SSH"
if [ -f /root/.ssh/authorized_keys ]; then
    cp /root/.ssh/authorized_keys "$DEPLOY_SSH/"
    echo "Copied SSH authorized_keys"
else
    echo "WARNING: /root/.ssh/authorized_keys not found — add SSH key manually to $DEPLOY_SSH/authorized_keys"
fi
chown -R "${DEPLOY_USER}:${DEPLOY_USER}" "$DEPLOY_SSH"
chmod 700 "$DEPLOY_SSH"
chmod 600 "$DEPLOY_SSH/authorized_keys" 2>/dev/null || true

# 4. Clone/copy repo to deploy home
if [ -d "$DEPLOY_DIR" ]; then
    echo "Directory $DEPLOY_DIR already exists, skipping copy"
else
    cp -r "$CURRENT_DIR" "$DEPLOY_DIR"
    echo "Copied $CURRENT_DIR -> $DEPLOY_DIR"
fi
chown -R "${DEPLOY_USER}:${DEPLOY_USER}" "$DEPLOY_DIR"

# 5. Copy .env if exists
if [ -f "${CURRENT_DIR}/.env" ] && [ ! -f "${DEPLOY_DIR}/.env" ]; then
    cp "${CURRENT_DIR}/.env" "${DEPLOY_DIR}/.env"
    chown "${DEPLOY_USER}:${DEPLOY_USER}" "${DEPLOY_DIR}/.env"
    echo "Copied .env"
fi

echo ""
echo "=== Done ==="
echo ""
echo "Next steps:"
echo "  1. Add GitHub Secret: DEPLOY_DIR=/home/${DEPLOY_USER}/todo"
echo "  2. Add GitHub Secret: SERVER_USER=${DEPLOY_USER}"
echo "  3. Test SSH: ssh ${DEPLOY_USER}@<server-ip> 'docker ps'"
echo "  4. Test deploy: push to master and check GitHub Actions"
echo ""
echo "To rollback: just change SERVER_USER back to root in GitHub Secrets"
