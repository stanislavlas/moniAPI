#!/bin/bash

echo "Initializing DynamoDB tables in LocalStack..."

# Wait for LocalStack to be ready
sleep 5

# AWS CLI endpoint and dummy credentials for LocalStack
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
ENDPOINT="http://localhost:4566"
REGION="eu-central-1"

# 1. Create users table
echo "Creating users table..."
aws --endpoint-url=$ENDPOINT dynamodb create-table \
  --table-name users \
  --key-schema \
    AttributeName=userId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=userId,AttributeType=S \
    AttributeName=email,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "email-index",
      "KeySchema": [{"AttributeName": "email", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST \
  --region $REGION

# 2. Create refresh_tokens table
echo "Creating refresh_tokens table..."
aws --endpoint-url=$ENDPOINT dynamodb create-table \
  --table-name refresh_tokens \
  --key-schema \
    AttributeName=tokenId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=tokenId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
    AttributeName=tokenPrefix,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "userId-index",
      "KeySchema": [{"AttributeName": "userId", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    },
    {
      "IndexName": "tokenPrefix-index",
      "KeySchema": [{"AttributeName": "tokenPrefix", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST \
  --region $REGION

# 3. Create categories table
echo "Creating categories table..."
aws --endpoint-url=$ENDPOINT dynamodb create-table \
  --table-name categories \
  --key-schema \
    AttributeName=categoryId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=categoryId,AttributeType=S \
    AttributeName=ownerKey,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "ownerKey-index",
      "KeySchema": [{"AttributeName": "ownerKey", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST \
  --region $REGION

# Seed global default categories (ownerKey = "global", isDefault = true)
echo "Seeding global default categories..."
NOW=$(date +%s)
for ROW in \
  "00000000-0000-0000-0000-000000000001|Salary|💼|#1D9E75|INCOME" \
  "00000000-0000-0000-0000-000000000002|Rent|🏠|#D85A30|EXPENSE" \
  "00000000-0000-0000-0000-000000000003|Energy|🔥|#EF9F27|EXPENSE" \
  "00000000-0000-0000-0000-000000000004|Groceries|🛒|#D4537E|EXPENSE" \
  "00000000-0000-0000-0000-000000000005|Transport|🚗|#BA7517|EXPENSE" \
  "00000000-0000-0000-0000-000000000006|Clothing|👕|#F0997B|EXPENSE" \
  "00000000-0000-0000-0000-000000000007|Subscription|📺|#AFA9EC|EXPENSE" \
  "00000000-0000-0000-0000-000000000008|Other|❓|#D3D1C7|EXPENSE" \
  "00000000-0000-0000-0000-000000000009|Stocks|📈|#7F77DD|INVESTMENT" \
  "00000000-0000-0000-0000-000000000010|Savings|🏦|#534AB7|INVESTMENT"
do
  IFS='|' read -r ID NAME EMOJI COLOR TYPE <<< "$ROW"
  aws --endpoint-url=$ENDPOINT dynamodb put-item \
    --table-name categories \
    --item "{
      \"categoryId\": {\"S\": \"$ID\"},
      \"ownerKey\":   {\"S\": \"global\"},
      \"name\":       {\"S\": \"$NAME\"},
      \"emoji\":      {\"S\": \"$EMOJI\"},
      \"color\":      {\"S\": \"$COLOR\"},
      \"type\":       {\"S\": \"$TYPE\"},
      \"isDefault\":  {\"BOOL\": true},
      \"createdAt\":  {\"N\": \"$NOW\"}
    }" \
    --region $REGION
done
echo "Global default categories seeded."

# 4. Create households table
echo "Creating households table..."
aws --endpoint-url=$ENDPOINT dynamodb create-table \
  --table-name households \
  --key-schema \
    AttributeName=householdId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=householdId,AttributeType=S \
    AttributeName=ownerId,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "ownerId-index",
      "KeySchema": [{"AttributeName": "ownerId", "KeyType": "HASH"}],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST \
  --region $REGION

# 5. Create entries table
echo "Creating entries table..."
aws --endpoint-url=$ENDPOINT dynamodb create-table \
  --table-name entries \
  --key-schema \
    AttributeName=entryId,KeyType=HASH \
  --attribute-definitions \
    AttributeName=entryId,AttributeType=S \
    AttributeName=userId,AttributeType=S \
    AttributeName=householdId,AttributeType=S \
    AttributeName=date,AttributeType=S \
  --global-secondary-indexes '[
    {
      "IndexName": "userId-date-index",
      "KeySchema": [
        {"AttributeName": "userId", "KeyType": "HASH"},
        {"AttributeName": "date", "KeyType": "RANGE"}
      ],
      "Projection": {"ProjectionType": "ALL"}
    },
    {
      "IndexName": "householdId-date-index",
      "KeySchema": [
        {"AttributeName": "householdId", "KeyType": "HASH"},
        {"AttributeName": "date", "KeyType": "RANGE"}
      ],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST \
  --region $REGION

echo "All tables created successfully!"

# List all tables
echo "Listing all tables..."
aws --endpoint-url=$ENDPOINT dynamodb list-tables --region $REGION

# 6. Create verification_codes table
echo "Creating verification_codes table..."
aws --endpoint-url=$ENDPOINT dynamodb create-table \
  --table-name verification_codes \
  --key-schema \
    AttributeName=code,KeyType=HASH \
  --attribute-definitions \
    AttributeName=code,AttributeType=S \
  --billing-mode PAY_PER_REQUEST \
  --region $REGION

echo "verification_codes table created."
