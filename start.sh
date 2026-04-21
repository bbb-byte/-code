#!/bin/bash

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$ROOT/.env"
ENV_EXAMPLE="$ROOT/.env.example"
RUNTIME_DIR="$ROOT/runtime/browser-profile"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${YELLOW}==============================================${NC}"
echo -e "${YELLOW} E-Commerce User Behavior Analysis - Start${NC}"
echo -e "${YELLOW}==============================================${NC}"

echo -e "\n${GREEN}[1/5] Checking Docker...${NC}"
if ! command -v docker >/dev/null 2>&1; then
  echo -e "${RED}Docker CLI not found. Please install Docker first.${NC}"
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo -e "${RED}Docker is not running. Please start Docker and retry.${NC}"
  exit 1
fi
echo -e "${GREEN}Docker is ready.${NC}"

echo -e "\n${GREEN}[2/5] Preparing environment files...${NC}"
if [ ! -f "$ENV_FILE" ]; then
  if [ ! -f "$ENV_EXAMPLE" ]; then
    echo -e "${RED}.env.example not found.${NC}"
    exit 1
  fi
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  echo -e "${YELLOW}Created .env from .env.example${NC}"
else
  echo -e "${GREEN}.env already exists.${NC}"
fi

echo -e "\n${GREEN}[3/5] Preparing runtime directories...${NC}"
mkdir -p "$RUNTIME_DIR"
echo -e "${GREEN}Runtime directory ready: $RUNTIME_DIR${NC}"
echo -e "${YELLOW}Using local MySQL from .env. Please make sure your host MySQL service is already running.${NC}"

echo -e "\n${GREEN}[4/5] Building and starting all services...${NC}"
cd "$ROOT"
docker compose up -d --build
echo -e "${GREEN}Docker services started.${NC}"

echo -e "\n${GREEN}[5/5] Checking service status...${NC}"
docker compose ps

echo -e "\n${YELLOW}==============================================${NC}"
echo -e "${GREEN}Startup completed.${NC}"
echo -e "${GREEN}Frontend: http://localhost${NC}"
echo -e "${GREEN}Backend:  http://localhost:8080/api${NC}"
echo -e "${GREEN}Swagger:  http://localhost:8080/api/swagger-ui.html${NC}"
echo -e "${GREEN}Worker:   http://localhost:8090/health${NC}"
echo -e "${YELLOW}If this is the first run, image build may take a while.${NC}"
echo -e "${YELLOW}==============================================${NC}"
