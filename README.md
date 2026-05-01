# 电商用户消费行为分析系统

基于 Vue 3 + Spring Boot 的电商用户消费行为分析系统，支持 archive/Kaggle 电商行为数据导入、清洗、RFM 分析、K-Means 用户分群、可视化展示，并提供京东公开评价摘要作为商品侧满意度补充指标。

## 项目功能

- **数据看板**：核心指标、行为分布、热门商品统计。
- **行为分析**：用户行为趋势、小时活跃度分析。
- **用户画像**：RFM 模型、K-Means 聚类分群。
- **商品分析**：热销商品排行、类目分析、公网满意度辅助解释。
- **转化漏斗**：浏览、加购、收藏、购买转化分析。
- **数据管理**：archive/Kaggle CSV 导入、统计更新、分析执行。
- **系统管理**：用户登录、用户管理、JWT 鉴权。
- **公网任务**：通过独立 `public-task-worker` 执行 Python/Node/Chromium 采集与映射任务。

## 技术栈

**前端**

- Vue 3 + Vite
- Element Plus
- ECharts / vue-echarts
- Vue Router + Pinia
- Nginx，Docker 部署时提供静态页面与 API 反向代理

**后端**

- Spring Boot 2.7.18
- MyBatis Plus
- Spring Security + JWT
- Redis 缓存
- Swagger / Springfox

**数据与任务**

- MySQL 8.0
- Redis 7
- Python 3.11 worker
- Node.js + Chromium，用于公网任务与浏览器接管

## 目录结构

```text
.
├── backend/                  # Spring Boot 后端
├── frontend/                 # Vue 3 前端
├── worker/                   # 公网任务 worker 服务
├── crawler/                  # Python 爬虫、映射、评分脚本
├── scripts/                  # CDP relay 等辅助脚本
├── archive/                  # 本地数据集目录，不纳入 Git
├── runtime/                  # 运行时目录、浏览器 profile、上传文件
├── docker-compose.yml        # Docker Compose 编排
├── .env.example              # 环境变量示例
├── start-windows.ps1         # Windows 一键启动脚本
├── start.sh                  # Linux/macOS 一键启动脚本
└── README.md
```

## 环境要求

推荐使用 Docker Compose 启动。宿主机需要：

- Docker / Docker Desktop
- MySQL 8.0，并提前启动
- Git Bash、WSL、Linux shell 或 PowerShell

Windows 一键启动公网任务接管能力时，还需要：

- Google Chrome
- Node.js 18+，用于启动 `scripts/cdp_relay.mjs`

仅做手动本机开发时，还需要：

- JDK 8+；Docker 镜像当前使用 JDK/JRE 17 构建运行
- Maven 3.8+
- Node.js 18+
- Python 3.11+
- Redis 7

## 快速启动

### 1. 准备 MySQL

本项目的 `docker-compose.yml` 不启动 MySQL 容器，后端默认连接宿主机 MySQL。

先创建数据库并执行初始化脚本：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ecommerce_analysis DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p ecommerce_analysis < backend/src/main/resources/sql/init.sql
```

然后复制环境变量文件：

```bash
cp .env.example .env
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

按本机 MySQL 修改 `.env`：

```env
MYSQL_HOST=host.docker.internal
MYSQL_PORT=3306
MYSQL_DATABASE=ecommerce_analysis
MYSQL_USERNAME=root
MYSQL_PASSWORD=你的MySQL密码
```

### 2. 一键启动，推荐

Windows：

```powershell
.\start-windows.ps1
```

或：

```bat
start-windows.bat
```

Linux/macOS：

```bash
./start.sh
```

依赖或 Dockerfile 变更后需要重建镜像：

```powershell
.\start-windows.ps1 -Build
```

```bash
./start.sh --build
```

启动脚本会完成：

- 检查 Docker 是否可用。
- 如果 `.env` 不存在，则从 `.env.example` 生成。
- 创建 `runtime/browser-profile` 运行时目录。
- 启动 `redis`、`backend`、`public-task-worker`、`frontend`。
- Windows 脚本会额外尝试启动 Chrome CDP 与 `9223` relay，便于 worker 复用宿主机浏览器登录态。

启动后访问：

- 前端页面：`http://localhost`
- 后端接口：`http://localhost:8080/api`
- Swagger：`http://localhost:8080/api/swagger-ui.html`
- Worker 健康检查：`http://localhost:8090/health`
- Windows CDP relay：`http://host.docker.internal:9223/json/version`

默认账号：

- 管理员：`admin / admin123`

### 3. 停止服务

Windows：

```powershell
.\stop-windows.ps1
```

Linux/macOS：

```bash
./stop.sh
```

或直接执行：

```bash
docker compose down --remove-orphans
```

停止脚本只停止项目容器、Windows CDP relay 和脚本启动的 Chrome CDP，不会停止宿主机 MySQL。

## 手动开发启动

手动启动适合开发调试。此模式下需要自己启动 MySQL 和 Redis。

启动 Redis：

```bash
docker run --name ecommerce-redis-dev -p 6379:6379 -d redis:7-alpine
```

启动后端：

```bash
cd backend
mvn spring-boot:run
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

本机开发访问地址：

- 前端：`http://localhost:3000`
- 后端：`http://localhost:8080/api`

如果本机 MySQL 用户、密码或端口不是默认值，可通过环境变量覆盖：

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/ecommerce_analysis?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
$env:SPRING_DATASOURCE_USERNAME="root"
$env:SPRING_DATASOURCE_PASSWORD="你的MySQL密码"
```

## Docker 服务说明

`docker-compose.yml` 当前包含：

- `redis`：缓存服务，端口 `6379`。
- `backend`：Spring Boot 后端，端口 `8080`，挂载项目根目录到 `/workspace`。
- `public-task-worker`：公网任务执行服务，端口 `8090`，负责 Python / Node / Chromium 任务。
- `frontend`：Nginx 前端服务，端口 `80`，代理 `/api` 到后端。
- 宿主机 MySQL：不由 Compose 管理，容器通过 `host.docker.internal` 访问。

常用命令：

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f public-task-worker
docker compose up -d --build
docker compose down --remove-orphans
```

## 公网任务与浏览器接管

公网满意度数据只作为商品侧补充指标，不是系统正式用户行为数据源。正式分析仍以 `archive` / Kaggle 行为数据为主。

相关环境变量：

- `PUBLIC_TASK_PYTHON`：worker 执行 Python 脚本使用的解释器。
- `PUBLIC_TASK_BROWSER_PATH`：Chromium/Chrome 可执行文件路径；Docker worker 默认使用 `/usr/bin/chromium`。
- `PUBLIC_TASK_BROWSER_CHANNEL`：Playwright 浏览器通道，例如 `chrome`。
- `PUBLIC_TASK_BROWSER_PROFILE_DIR`：浏览器 profile 目录，Docker 推荐 `/workspace/runtime/browser-profile`。
- `PUBLIC_TASK_WORKER_URL`：后端访问 worker 的地址，Docker 内部为 `http://public-task-worker:8090`。
- `PUBLIC_TASK_WORKSPACE_ROOT`：容器内工作区根目录，默认 `/workspace`。
- `PUBLIC_TASK_CDP_URL`：宿主机浏览器 CDP 地址，Windows 脚本默认配置为 `http://host.docker.internal:9223`。

Windows 推荐直接运行 `.\start-windows.ps1`。脚本会：

1. 使用 `C:\chrome-jd-profile` 启动一个带远程调试端口的 Chrome。
2. 启动 `scripts/cdp_relay.mjs`，监听 `0.0.0.0:9223` 并转发到本机 Chrome 的 `127.0.0.1:9222`。
3. 将 `.env` 中的 `PUBLIC_TASK_CDP_URL` 设置为 `http://host.docker.internal:9223`。

如果需要手动启动 Chrome，可使用：

```powershell
chrome.exe --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222 --user-data-dir=C:\chrome-jd-profile
```

如果 Chrome 只监听 `127.0.0.1:9222`，Docker 容器无法直接访问，此时需要同时启动 relay：

```bat
start-cdp-relay.bat
```

公网任务常见检查：

```bash
docker compose logs -f public-task-worker
curl http://localhost:8090/health
```

## 数据导入流程

1. 登录系统。
2. 进入“数据管理”页面。
3. 输入 archive/Kaggle CSV 文件路径，例如 `archive/2019-Nov-demo.csv`。
4. 点击“开始导入”。
5. 导入完成后点击“执行分析”，计算 RFM、聚类和统计指标。

公网满意度补充流程：

1. 先导入 archive/Kaggle 行为文件，完成主分析数据入库。
2. 在“公网映射工作台”填写原始行为文件路径。
3. 点击“召回候选商品”，生成商品快照与候选商品。
4. 点击“计算映射分数”，生成评分结果预览。
5. 检查候选商品，必要时编辑标题、置信度、核验说明和证据备注。
6. 勾选通过候选，点击“一键确认入库”，写入 `product_public_mapping`。
7. 回到“公网满意度补充”，点击“采集公网满意度指标”。

## 常见问题

**后端连不上数据库**

- 确认宿主机 MySQL 已启动。
- 确认 `.env` 中 `MYSQL_*` 配置正确。
- Docker 场景通常使用 `MYSQL_HOST=host.docker.internal`。

**后端提示 worker 不可达**

- 执行 `docker compose ps`，确认 `public-task-worker` 正在运行。
- 确认 `.env` 中 `PUBLIC_TASK_WORKER_URL=http://public-task-worker:8090`。
- 查看 `docker compose logs -f public-task-worker`。

**公网任务浏览器启动失败**

- Docker worker 默认需要 Chromium，重建镜像可重新安装依赖：`docker compose up -d --build`。
- Windows 复用宿主机登录态时，确认 `http://host.docker.internal:9223/json/version` 可访问。
- 登录京东等网站时，需要先在脚本打开的 Chrome 窗口完成登录。

**改了依赖但启动后没生效**

- 前端依赖、Python 依赖、Dockerfile 修改后，使用 `--build` 或 `-Build` 重新构建。

## 核心算法

### RFM 模型

- **R (Recency)**：最近一次消费距当前基准日期的天数。
- **F (Frequency)**：消费频率，通常为购买次数。
- **M (Monetary)**：消费金额总和。

### K-Means 聚类

基于用户 RFM 特征使用 K-Means++ 进行分群，默认分为：

- 高价值用户
- 潜力用户
- 新用户
- 沉睡用户
- 流失用户

## 答辩说明材料

- [工程质量与复现性答辩说明](docs/DEFENSE_ENGINEERING_NOTES.md)
- [数据字典](docs/DATA_DICTIONARY.md)
- [接口与测试数据说明](docs/API_AND_TEST_DATA.md)

## 作者

- 姓名：雷明浩
- 学号：220593026
- 专业：数据科学与大数据技术
- 指导教师：李娟 教授

## License

MIT License
