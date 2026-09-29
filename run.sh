#!/usr/bin/with-contenv bashio

bashio::log.info "Starting Personal Finance Backend..."

# Read JWT secret from HA add-on options (configured in the HA UI)
JWT_SECRET=$(bashio::config 'jwt_secret')
export JWT_SECRET

# Read CORS allowed origins from HA add-on options
CORS_ALLOWED_ORIGINS=$(bashio::config 'cors_allowed_origins')
export CORS_ALLOWED_ORIGINS

# LocalStack runs on port 4566 inside the Docker container
export AWS_URL=http://localhost:4566

bashio::log.info "Waiting for LocalStack to be reachable..."

MAX_RETRIES=24  # 24 × 5 s = 2 minutes
retries=0
until curl -s "http://localhost:4566/_localstack/health" > /dev/null 2>&1; do
    retries=$((retries + 1))
    if [ "$retries" -ge "$MAX_RETRIES" ]; then
        bashio::log.error "LocalStack did not become ready after $((MAX_RETRIES * 5)) seconds. Exiting."
        exit 1
    fi
    bashio::log.info "LocalStack not reachable yet, retrying in 5s... ($retries/$MAX_RETRIES)"
    sleep 5
done

bashio::log.info "LocalStack is reachable. Starting Spring Boot..."

exec java -jar /app/app.jar
