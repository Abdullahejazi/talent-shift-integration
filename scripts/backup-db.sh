#!/bin/bash
# A script to backup the PostgreSQL database from the running production container.
# This should be executed by a cron job on the production server (e.g., daily at 2:00 AM).

# Exit immediately if a command exits with a non-zero status
set -e

# Configuration
CONTAINER_NAME="talentshift-core-prod-postgres-1"
DB_USER="talentshift"
DB_NAME="talentshift"
BACKUP_DIR="./backups"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="${BACKUP_DIR}/db_backup_${TIMESTAMP}.sql.gz"

# Ensure backup directory exists
mkdir -p "$BACKUP_DIR"

echo "Starting database backup at $TIMESTAMP..."

# Run pg_dump inside the container and compress it on the fly
docker exec "$CONTAINER_NAME" pg_dump -U "$DB_USER" -d "$DB_NAME" -C -c | gzip > "$BACKUP_FILE"

echo "Backup successfully saved to $BACKUP_FILE"

# Optional: Delete backups older than 30 days to save space
# find "$BACKUP_DIR" -type f -name "*.sql.gz" -mtime +30 -exec rm {} \;
# echo "Cleaned up backups older than 30 days."
