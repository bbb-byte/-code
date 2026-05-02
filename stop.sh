#!/bin/bash

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${YELLOW}==============================================${NC}"
echo -e "${YELLOW} E-Commerce User Behavior Analysis - Stop${NC}"
echo -e "${YELLOW}==============================================${NC}"

echo -e "\n${GREEN}[1/3] Checking Docker...${NC}"
if ! command -v docker >/dev/null 2>&1; then
  echo -e "${RED}Docker CLI not found. Please install Docker first.${NC}"
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo -e "${RED}Docker is not running. Please start Docker and retry.${NC}"
  exit 1
fi
echo -e "${GREEN}Docker is ready.${NC}"

echo -e "\n${GREEN}[2/3] Stopping compose services...${NC}"
cd "$ROOT"
docker compose down --remove-orphans
echo -e "${GREEN}Compose services stopped.${NC}"

echo -e "\n${GREEN}[3/3] Notes${NC}"
echo -e "${YELLOW}Compose-managed MySQL is stopped with the project. Data remains in the mysql_data Docker volume.${NC}"

echo -e "\n${YELLOW}==============================================${NC}"
echo -e "${GREEN}Stop completed.${NC}"
echo -e "${YELLOW}==============================================${NC}"
