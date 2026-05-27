#!/bin/bash
set -e

for jar in \
    "fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
    "dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar" \
    "grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar"; do
    if [ ! -f "$jar" ]; then
        exit 1
    fi
done

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

aws iam create-role --role-name CNV-Lambda-Role \
    --assume-role-policy-document '{
      "Version":"2012-10-17",
      "Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]
    }' > /dev/null 2>&1 || true

aws iam attach-role-policy --role-name CNV-Lambda-Role \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole 2>/dev/null || true

LAMBDA_ROLE_ARN=$(aws iam get-role --role-name CNV-Lambda-Role --query Role.Arn --output text)
sleep 10

deploy_function() {
    local name=$1
    local jar=$2
    local handler=$3

    if aws lambda get-function --function-name "$name" > /dev/null 2>&1; then
        aws lambda update-function-code --function-name "$name" \
            --zip-file "fileb://$jar" > /dev/null
    else
        aws lambda create-function --function-name "$name" \
            --runtime java17 \
            --handler "$handler" \
            --role "$LAMBDA_ROLE_ARN" \
            --memory-size 512 \
            --timeout 60 \
            --zip-file "fileb://$jar" > /dev/null
    fi
}

deploy_function cnv-fractals \
    fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    pt.ulisboa.tecnico.cnv.fractals.FractalsHandler::handleRequest

deploy_function cnv-dna \
    dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    pt.ulisboa.tecnico.cnv.dna.DnaHandler::handleRequest

deploy_function cnv-grayscott \
    grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler::handleRequest

aws iam put-role-policy --role-name CNV-LB-Role \
    --policy-name CNV-LB-Lambda-CW-Policy \
    --policy-document "{
      \"Version\":\"2012-10-17\",
      \"Statement\":[
        {
          \"Effect\":\"Allow\",
          \"Action\":\"lambda:InvokeFunction\",
          \"Resource\":\"arn:aws:lambda:*:${ACCOUNT_ID}:function:cnv-*\"
        },
        {
          \"Effect\":\"Allow\",
          \"Action\":\"cloudwatch:GetMetricStatistics\",
          \"Resource\":\"*\"
        }
      ]
    }" 2>/dev/null || true

