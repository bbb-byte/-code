#!/bin/bash

# 电商用户消费行为分析系统 - 一键停止脚本 (Mac版)

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}==============================================${NC}"
echo -e "${YELLOW}   电商用户消费行为分析系统 - 停止程序${NC}"
echo -e "${YELLOW}==============================================${NC}"

# 1. 停止后端服务 (端口 8080)
echo -e "\n${GREEN}[1/3] 正在停止后端服务 (Port 8080)...${NC}"
BE_PID=$(lsof -t -i:8080)
if [ -n "$BE_PID" ]; then
    kill -9 $BE_PID
    echo -e "后端服务 (PID: $BE_PID) 已停止。"
else
    echo -e "未发现运行在 8080 端口的后端服务。"
fi

# 2. 停止前端服务 (端口 3000)
echo -e "\n${GREEN}[2/3] 正在停止前端服务 (Port 3000)...${NC}"
FE_PID=$(lsof -t -i:3000)
if [ -n "$FE_PID" ]; then
    kill -9 $FE_PID
    echo -e "前端服务 (PID: $FE_PID) 已停止。"
else
    echo -e "未发现运行在 3000 端口的前端服务。"
fi

# 3. 停止 Docker 容器
echo -e "\n${GREEN}[3/3] 正在停止 Docker 容器...${NC}"

if [ "$(docker ps -q -f name=mysql-ecommerce)" ]; then
    echo "停止 MySQL 容器..."
    docker stop mysql-ecommerce
else
    echo "MySQL 容器未在运行。"
fi

if [ "$(docker ps -q -f name=redis-ecommerce)" ]; then
    echo "停止 Redis 容器..."
    docker stop redis-ecommerce
else
    echo "Redis 容器未在运行。"
fi

echo -e "\n${YELLOW}==============================================${NC}"
echo -e "${GREEN}所有服务已停止！${NC}"
echo -e "${YELLOW}==============================================${NC}"
