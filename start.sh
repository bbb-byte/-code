#!/bin/bash

# 电商用户消费行为分析系统 - 一键启动脚本 (Mac版)

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${YELLOW}==============================================${NC}"
echo -e "${YELLOW}   电商用户消费行为分析系统 - 启动程序${NC}"
echo -e "${YELLOW}==============================================${NC}"

# 1. 检查 Docker 状态
echo -e "\n${GREEN}[1/4] 检查 Docker 服务...${NC}"
if ! docker info > /dev/null 2>&1; then
    echo -e "${YELLOW}Docker 未启动，正在尝试启动 Docker Desktop...${NC}"
    open -a Docker
    echo -e "${YELLOW}请等待 Docker 启动完成后重新运行此脚本。${NC}"
    exit 1
fi
echo -e "Docker 已就绪。"

# 2. 启动基础服务 (MySQL & Redis)
echo -e "\n${GREEN}[2/4] 启动 MySQL 和 Redis 容器...${NC}"
# 检查是否已有容器在运行
if [ ! "$(docker ps -q -f name=mysql-ecommerce)" ]; then
    if [ "$(docker ps -aq -f name=mysql-ecommerce)" ]; then
        echo "启动已存在的 MySQL 容器..."
        docker start mysql-ecommerce
    else
        echo "创建并启动新的 MySQL 容器..."
        docker run -d --name mysql-ecommerce \
          -p 3306:3306 \
          -e MYSQL_ROOT_PASSWORD=root123 \
          -e MYSQL_DATABASE=ecommerce_analysis \
          -e TZ=Asia/Shanghai \
          mysql:8.0 \
          --default-authentication-plugin=mysql_native_password
    fi
fi

if [ ! "$(docker ps -q -f name=redis-ecommerce)" ]; then
    if [ "$(docker ps -aq -f name=redis-ecommerce)" ]; then
        echo "启动已存在的 Redis 容器..."
        docker start redis-ecommerce
    else
        echo "创建并启动新的 Redis 容器..."
        docker run -d --name redis-ecommerce -p 6379:6379 redis:7-alpine
    fi
fi

# 3. 等待数据库就绪
echo -e "\n${GREEN}[3/4] 等待数据库初始化...${NC}"
MAX_RETRIES=30
COUNT=0
while ! docker exec mysql-ecommerce mysql -uroot -proot123 -e "SELECT 1" > /dev/null 2>&1; do
    sleep 2
    COUNT=$((COUNT+1))
    if [ $COUNT -ge $MAX_RETRIES ]; then
        echo -e "${RED}数据库启动超时，请检查 Docker 日志。${NC}"
        exit 1
    fi
    echo -n "."
done
echo -e "\n数据库已连接。"

# 4. 启动前后端
echo -e "\n${GREEN}[4/4] 正在打开新终端启动前后端服务...${NC}"

# 使用 AppleScript 打开新终端运行后端
osascript -e "tell application \"Terminal\" to do script \"cd '$(pwd)/backend' && mvn spring-boot:run\""
echo -e "已在后台终端启动 ${YELLOW}后端服务 (Port 8080)${NC}"

# 使用 AppleScript 打开新终端运行前端
osascript -e "tell application \"Terminal\" to do script \"cd '$(pwd)/frontend' && npm run dev\""
echo -e "已在后台终端启动 ${YELLOW}前端服务 (Port 3000)${NC}"

echo -e "\n${YELLOW}==============================================${NC}"
echo -e "${GREEN}启动指令已发送！${NC}"
echo -e "请在弹出的终端窗口中查看运行日志。"
echo -e "系统访问地址: ${GREEN}http://localhost:3000${NC}"
echo -e "API文档地址: ${GREEN}http://localhost:8080/api/swagger-ui.html${NC}"
echo -e "${YELLOW}==============================================${NC}"
