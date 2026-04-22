# 电商用户消费行为分析系统

基于 Vue3 + SpringBoot 的电商用户消费行为分析系统，实现 archive 多品类电商行为数据的导入、清洗、分析与可视化展示。

## 项目介绍

本系统为武汉商学院本科毕业论文项目，旨在通过对电商用户行为数据的分析，帮助企业掌握用户消费偏好、高价值用户特征，实现精准营销与精细化运营。

### 主要功能

- **数据看板**：展示核心指标、行为分布、热门商品等
- **行为分析**：用户行为趋势、每小时活跃度分析
- **用户画像**：RFM模型分析、K-Means聚类分群
- **商品分析**：热销商品排行、类目分析
- **转化漏斗**：浏览→加购→收藏→购买转化率分析
- **数据导入**：支持 archive 电商行为数据集导入与统计更新
- **公网补充指标**：保留 Python 爬虫作为京东公开评价摘要补充采集工具，不作为当前正式分析口径的数据主源
- **系统管理**：用户管理、数据导入、分析执行

### 技术栈

**前端**
- Vue 3 + Vite
- Element Plus UI
- ECharts 可视化
- Vue Router + Pinia

**后端**
- Spring Boot 2.7.18 (兼容 Java 8)
- MyBatis Plus
- Spring Security + JWT
- Redis 缓存

**数据库**
- MySQL 8.0

**部署**
- Docker + Docker Compose
- Nginx

## 目录结构

```
code/
├── backend/                 # 后端 SpringBoot 项目
│   ├── src/main/java/      # Java 源码
│   ├── src/main/resources/ # 配置文件
│   └── pom.xml             # Maven 配置
│
├── frontend/               # 前端 Vue3 项目
│   ├── src/               # Vue 源码
│   └── package.json       # npm 配置
│
├── archive/              # 本地 archive 数据集目录（不纳入 Git）
├── docker-compose.yml     # Docker 编排配置
└── README.md              # 项目说明
```

## 快速开始

### 环境要求

- JDK 1.8 (Java 8)
- Node.js 18+
- MySQL 8.0
- Redis 7.0
- Maven 3.8+

## 快速开始

### 1. 一键启动 (推荐)

在项目根目录下运行：
```bash
./start.sh
```
Windows 可使用：
```powershell
.\start-windows.ps1
```
或：
```bat
start-windows.bat
```

这些脚本会自动完成以下操作：

- 检查 Docker / Docker Desktop 是否可用
- 若 `.env` 不存在，则从 `.env.example` 自动生成
- 自动创建 `runtime/browser-profile` 目录
- 默认执行 `docker compose up -d`，复用已有镜像以加快启动
- 需要重新构建镜像时，可使用 `./start.sh --build`、`.\start-windows.ps1 -Build` 或 `start-windows.bat --build`
- 一次性启动 `redis`、`backend`、`public-task-worker`、`frontend`
- 后端通过 `.env` 中的 `MYSQL_*` 配置连接宿主机 MySQL，不再额外启动 MySQL 容器
该脚本会自动检查 Docker 环境、启动数据库容器，并在新终端窗口中启动前后端服务。

### 2. 手动启动

#### 数据库初始化
```bash
# 登录 MySQL 并执行初始化脚本
mysql -u root -p < backend/src/main/resources/sql/init.sql
```

#### 启动后端
```bash
cd backend
mvn spring-boot:run
```

#### 启动前端
```bash
cd frontend
npm install
npm run dev
```

前端启动后访问: http://localhost:3000

### 4. 默认账号

- 管理员: admin / admin123

## Docker 部署

```bash
# 快速启动所有服务（默认不重建）
docker compose up -d

# 依赖或 Dockerfile 变更后重新构建
docker compose up -d --build

# 查看日志
docker compose logs -f

# 停止服务
docker compose down --remove-orphans
```

## 数据导入

1. 登录系统（管理员账号）
2. 进入 "数据管理" 页面
3. 输入 archive CSV 文件路径（如 `archive/2019-Nov-demo.csv`），点击 "开始导入"
4. 导入完成后，点击 "执行分析" 计算 RFM 值和聚类

## 公网满意度补充数据

- `archive` 数据集仍然是本系统唯一的正式用户行为与分群主数据源。
- Python 爬虫在当前版本只负责补充 `JD public product satisfaction data（京东公开评价摘要）` 这一类商品侧公开指标，例如：
  - `positive_rate`
  - `review_count`
  - `shop_score`
- 这些指标只用于辅助解释 RFM 分群和商品表现，例如分析高价值流失用户是否集中购买了低口碑商品。
- 该补充模块不是平台内部点击日志，也不用于重建真实的用户级浏览→加购→购买链路。

管理员接口约定：
- `POST /api/data/crawl`：根据已存在的公网映射抓取商品满意度指标；支持传 `mappingPath/outputDir/fixtureDir`
- `POST /api/data/public-mapping/recall`：生成公网候选商品；支持直接传商品快照，也支持传原始 Kaggle/archive 行为文件并由后端自动生成商品快照

推荐操作步骤：
1. 在“数据导入”中先导入 `archive` / Kaggle 行为文件，完成主分析数据入库
2. 在“公网映射工作台”中填写原始行为文件路径，例如 `archive/2019-Nov-demo.csv`
3. 点击“召回候选商品”，系统会自动生成商品快照并输出候选商品文件
   说明：召回完成后，工作台会自动切换到新生成的商品快照/候选文件；下一步应立即执行“计算映射分数”
4. 点击“计算映射分数”，生成评分结果预览
5. 在评分结果预览中检查候选商品，必要时点“编辑”补充标题、置信度、核验说明和证据备注
6. 勾选通过的候选，点击“一键确认入库”，将结果写入 `product_public_mapping`
7. 最后回到“公网满意度补充”，点击“采集公网满意度指标”，按已确认映射抓取京东公开评价摘要

## 核心算法

### RFM 模型

- **R (Recency)**: 最近一次消费距今天数
- **F (Frequency)**: 消费频率（购买次数）
- **M (Monetary)**: 消费金额总和

### K-Means 聚类

基于用户 RFM 特征，使用 K-Means++ 算法进行用户分群，默认分为 5 类：
- 高价值用户
- 潜力用户
- 新用户
- 沉睡用户
- 流失用户

## 作者

- 姓名：雷明浩
- 学号：220593026
- 专业：数据科学与大数据技术
- 指导教师：李娟 教授

## License

MIT License

## Start / Stop Notes

- Start scripts now use Docker Compose to start `redis`, `backend`, `public-task-worker`, and `frontend`.
- Start scripts default to `docker compose up -d` for faster restarts. Use the build flag only when dependencies or Dockerfiles change.
- Local MySQL is not started by Docker. Please make sure your host MySQL service is already running before executing `start.sh`, `start-windows.ps1`, or `start-windows.bat`.
- Stop scripts now use `docker compose down --remove-orphans` to stop the project containers cleanly.
- Local MySQL is not managed by the stop scripts and will remain running.
- Current access addresses after startup:
  Frontend: `http://localhost`
  Backend: `http://localhost:8080/api`
  Swagger: `http://localhost:8080/api/swagger-ui.html`
  Worker health: `http://localhost:8090/health`

## 公网任务运行配置

为避免把作者本机路径硬编码进项目，公网任务相关脚本现已统一改为通过环境变量读取运行配置，并支持通过独立 `worker` 容器执行。

### 1. 配置环境变量

先将 `.env.example` 复制为 `.env`，再按当前机器或容器环境修改配置项。

支持的环境变量：

- `PUBLIC_TASK_PYTHON`：后端或 `worker` 执行公网任务时使用的 Python 解释器
- `PUBLIC_TASK_BROWSER_PATH`：浏览器可执行文件的显式路径
- `PUBLIC_TASK_BROWSER_CHANNEL`：Playwright 自动发现浏览器时使用的通道，例如 `chrome`
- `PUBLIC_TASK_BROWSER_PROFILE_DIR`：浏览器 Profile 目录，用于复用登录态和缓存
- `PUBLIC_TASK_WORKER_URL`：后端转发公网任务到 `worker` 的地址
- `PUBLIC_TASK_WORKSPACE_ROOT`：容器内工作区根目录

推荐配置示例：

```bash
# Linux / Docker
PUBLIC_TASK_PYTHON=/usr/bin/python3
PUBLIC_TASK_BROWSER_PATH=/usr/bin/chromium
PUBLIC_TASK_BROWSER_CHANNEL=chrome
PUBLIC_TASK_BROWSER_PROFILE_DIR=/workspace/runtime/browser-profile
PUBLIC_TASK_WORKER_URL=http://public-task-worker:8090
PUBLIC_TASK_WORKSPACE_ROOT=/workspace
```

```powershell
# Windows 本机开发
$env:PUBLIC_TASK_PYTHON="C:/Python311/python.exe"
$env:PUBLIC_TASK_BROWSER_PATH="C:/Program Files/Google/Chrome/Application/chrome.exe"
$env:PUBLIC_TASK_BROWSER_CHANNEL="chrome"
$env:PUBLIC_TASK_BROWSER_PROFILE_DIR="./runtime/browser-profile"
```

### 2. Docker 启动方式

执行以下命令启动：

```bash
docker-compose up -d --build
```

查看关键服务状态：

```bash
docker-compose ps
docker-compose logs -f backend
docker-compose logs -f public-task-worker
```

### 3. 当前容器分工

- `backend`：负责接口、任务编排、结果入库
- `public-task-worker`：负责执行 Python / Node / Chromium 公网任务脚本
- `redis`：负责缓存
- 宿主机 MySQL：负责数据库，容器通过 `host.docker.internal` 和 `.env` 中的 `MYSQL_*` 配置连接
- `frontend`：负责前端页面服务

### 4. 当前 Docker 编排说明

- `docker-compose.yml` 已将 `PUBLIC_TASK_*` 环境变量透传到 `backend`
- `backend` 挂载整个项目目录到 `/workspace`，用于统一访问脚本、输入输出目录和运行时文件
- `public-task-worker` 镜像在构建阶段会安装 `crawler/requirements.txt` 与 `frontend/package.json` 中的任务依赖
- `public-task-worker` 运行阶段挂载 `crawler/`、`frontend/scripts/`、`runtime/browser-profile/`
- 后端通过 `PUBLIC_TASK_WORKER_URL` 将公网任务转发给 `worker` 执行

这样处理后，公网任务不再依赖 `backend` 镜像内必须安装 Python、Node、Chromium，而是交由独立 `worker` 容器执行，更便于跨机器复现与后续扩展。

### 5. 运行后如何验证

可以按下面顺序检查：

1. 执行 `docker-compose ps`，确认 `backend`、`public-task-worker`、`redis` 均为运行状态。
2. 确认宿主机 MySQL 服务已启动，并且 `.env` 中的 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 配置正确。
3. 查看 `public-task-worker` 日志，确认服务正常启动，没有 Python、npm、Chromium 缺失报错。
4. 访问后端接口或页面，触发一次公网任务。
5. 检查 `crawler/output/` 是否生成结果文件，检查 `runtime/browser-profile/` 是否生成浏览器状态目录。

### 6. 常见问题排查

- 若后端提示无法连接 `worker`，先检查 `PUBLIC_TASK_WORKER_URL` 是否与 `docker-compose` 中服务名一致。
- 若 `worker` 内浏览器启动失败，优先检查 `PUBLIC_TASK_BROWSER_PATH` 是否正确，或暂时留空改用自动发现。
- 若脚本报依赖缺失，需要重新执行 `docker-compose up -d --build`，确保 `worker` 镜像已重新构建。
- 若输出路径不一致，重点检查 `PUBLIC_TASK_WORKSPACE_ROOT`、挂载目录和传入的相对路径是否都基于 `/workspace`。

### 7. 健康检查与报错改进

当前后端在派发公网任务前，会先访问 `worker` 的 `/health` 接口做可用性检查。

如果出现异常，通常会直接区分为以下几类：

- `worker` 不可达：通常表示 `public-task-worker` 容器未启动，或 `PUBLIC_TASK_WORKER_URL` 配置错误
- Python 解释器不可用：通常表示 `PUBLIC_TASK_PYTHON` 配错，或当前运行环境未安装 Python
- 浏览器启动失败：通常表示 `PUBLIC_TASK_BROWSER_PATH` 不正确，或容器内浏览器依赖未就绪
- 脚本执行超时：后端会明确提示超时时间和对应命令，便于定位是网络、页面加载还是风控问题

### 8. 答辩说明材料

如需说明“工程质量与复现性”改造思路，可参考：

- [工程质量与复现性答辩说明](D:/桌面/论文/code/-code/docs/DEFENSE_ENGINEERING_NOTES.md)
# 宿主机登录 Chrome（供 Docker worker 通过 CDP 接管）

在 Windows 宿主机上启动已登录的 Chrome 时，推荐使用：

```powershell
chrome.exe --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222 --user-data-dir=C:\chrome-jd-profile
```

如果只写 `--remote-debugging-port=9222`，Chrome 往往只监听 `127.0.0.1`，`public-task-worker` 容器通过 `host.docker.internal` 访问时会看到 `ECONNREFUSED 192.168.65.254:9222` 一类错误。
