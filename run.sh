#!/usr/bin/with-contenv bashio

bashio::log.info "Starting Personal Finance Backend..."

# Read JWT secret from HA add-on options (configured in the HA UI)
JWT_SECRET=$(bashio::config 'jwt_secret')
export JWT_SECRET

bashio::log.info "Waiting for LocalStack to be reachable..."

until curl -s "http://localhost:4566" > /dev/null 2>&1; do
    bashio::log.info "LocalStack not reachable yet, retrying in 5s..."
    sleep 5
done

bashio::log.info "LocalStack is reachable. Starting Spring Boot..."

exec java -jar /app/app.jar \
    --spring.config.location=/app/application.properties
