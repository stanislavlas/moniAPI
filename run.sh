#!/usr/bin/with-contenv bashio

bashio::log.info "Starting Personal Finance Backend..."

# Read JWT secret from HA add-on options (configured in the HA UI)
JWT_SECRET=$(bashio::config 'jwt_secret')
export JWT_SECRET

# Read CORS allowed origins from HA add-on options
CORS_ALLOWED_ORIGINS=$(bashio::config 'cors_allowed_origins')
export CORS_ALLOWED_ORIGINS

# Read VAPID keys for web push notifications from HA add-on options
VAPID_PUBLIC_KEY=$(bashio::config 'vapid_public_key')
export VAPID_PUBLIC_KEY

VAPID_PRIVATE_KEY=$(bashio::config 'vapid_private_key')
export VAPID_PRIVATE_KEY

VAPID_SUBJECT=$(bashio::config 'vapid_subject')
export VAPID_SUBJECT

bashio::log.info "Waiting for LocalStack to be reachable..."

until curl -s "http://localhost:4566" > /dev/null 2>&1; do
    bashio::log.info "LocalStack not reachable yet, retrying in 5s..."
    sleep 5
done

bashio::log.info "LocalStack is reachable. Starting Spring Boot..."

exec java -jar /app/app.jar \
    --spring.config.location=/app/application.properties
