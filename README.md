# 电商用户消费行为分析系统

基于 Vue 3 + Spring Boot + MySQL + Redis 的电商用户消费行为分析系统，支持 archive/Kaggle 行为数据导入、RFM 分析、K-Means 用户分群、可视化展示，以及京东公网评价摘要采集作为商品侧满意度补充指标。

## 功能概览

- **数据看板**：核心指标、行为分布、热门商品统计。
- **行为分析**：用户行为趋势、小时活跃度分析。
- **用户画像**：RFM 模型、K-Means 聚类分群。
- **商品分析**：热销商品排行、类目分析、公开满意度补充指标。
- **数据管理**：CSV 上传导入、统计更新、分析任务执行、公网映射工作流。
- **系统管理**：登录、用户管理、JWT 鉴权。
- **公网任务**：通过独立 `public-task-worker` 执行 Python/Node/Chromium 采集与映射任务。

## 目录结构

```text
.
├── backend/                  # Spring Boot 后端
├── frontend/                 # Vue 3 前端
│   └── scripts/              # Node.js 浏览器自动化脚本
├── worker/                   # 公网任务 worker 服务
├── crawler/                  # Python 爬虫、映射、评分脚本
│   └── fixtures/             # 测试用固定输入数据
├── scripts/                  # CDP relay 等辅助脚本
├── archive/                  # 本地数据集目录（不纳入 Git，需手动放置）
├── runtime/                  # 运行时目录，自动创建（不纳入 Git）
├── docker-compose.yml        # Docker Compose 编排
├── .env.example              # 环境变量示例
├── start-windows.bat         # Windows 一键启动脚本
├── start-windows.ps1         # Windows PowerShell 启动脚本
├── start.sh                  # Linux/macOS 一键启动脚本
└── README.md
```

## 环境要求

对方电脑**必须**安装：

- **Docker Desktop**（Windows/macOS）或 Docker Engine（Linux）
- Git 或能解压项目压缩包的工具

如需使用**京东公网采集、候选召回**等功能，还需要：

- **Google Chrome**（宿主机上）
- **Node.js 18+**（用于启动 CDP relay）

普通数据导入、RFM 分析、K-Means 聚类**不需要** Chrome 和 Node.js。

## 快速启动

### 1. 准备环境变量

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

Linux/macOS：

```bash
cp .env.example .env
```

默认配置使用 Compose 内置 MySQL，**不需要**在宿主机另外安装 MySQL：

```env
MYSQL_HOST=mysql
MYSQL_PORT=3306
MYSQL_HOST_PORT=3307      # 映射到宿主机的端口，避开本机常用的 3306
MYSQL_DATABASE=ecommerce_analysis
MYSQL_USERNAME=root
MYSQL_PASSWORD=root123456
```

> 如果宿主机 3307 端口已被占用，把 `.env` 中 `MYSQL_HOST_PORT` 改成其他未占用端口（如 `3308`）即可，无需改其他配置。

### 2. 启动服务

**Windows（推荐，双击或在 PowerShell 中执行）**：

```bat
start-windows.bat
```

或：

```powershell
.\start-windows.ps1
```

**Linux/macOS**：

```bash
chmod +x start.sh
./start.sh --build
```

> **首次启动**建议带 `--build`（PowerShell 用 `-Build`），让 Docker 构建镜像并安装依赖。  
> 后续不改依赖时，可以直接不带参数运行，复用已有镜像快速启动。

`start-windows.bat` 会自动完成：

1. 检查并等待 Docker 就绪。
2. 从 `.env.example` 初始化 `.env`（已存在则跳过）。
3. 启动所有 Docker 服务。
4. 尝试启动带远程调试端口的 Chrome（`C:\chrome-jd-profile`）。
5. 启动 CDP relay（`scripts/cdp_relay.mjs`，9223 端口）。

### 3. 访问系统

| 服务 | 地址 |
|---|---|
| 前端页面 | http://localhost |
| 后端接口 | http://localhost:8080/api |
| Swagger 文档 | http://localhost:8080/api/swagger-ui.html |
| Worker 健康检查 | http://localhost:8090/health |

默认账号：**`admin / admin123`**

## 停止服务

Windows：

```powershell
.\stop-windows.ps1
```

Linux/macOS：

```bash
./stop.sh
```

或直接：

```bash
docker compose down --remove-orphans
```

如需同时清除数据库和 Redis 数据（**不可恢复**）：

```bash
docker compose down -v --remove-orphans
```

## 数据导入与分析

1. 登录系统，进入**数据管理**页面。
2. 上传 archive/Kaggle CSV 文件（支持拖拽上传），或填写容器可访问的 CSV 路径。
3. 点击**开始导入**，等待进度条完成。
4. 导入完成后点击**执行分析**，系统自动计算 RFM 模型、K-Means 聚类和商品统计指标。

数据集可放在项目根目录的 `archive/` 下，例如：

```text
archive/2019-Nov-demo.csv
archive/2020-Apr-demo.csv
```

> `archive/` 目录已在 `.gitignore` 中排除，不会被提交到 Git。

## 公网商品映射工作流

本功能通过浏览器自动搜索京东，将内部商品与公网商品关联，并采集满意度指标（好评率、评价数等）。完整流程分以下五步，均在**数据管理**页面操作：

```
上传行为数据 CSV
       ↓
① 召回候选商品（京东搜索，自动提取候选 SKU）
       ↓
② 计算映射分数（按品牌、标题、价格多维评分）
       ↓
③ 刷新评分预览（查看评分结果，可手动调整）
       ↓
④ 一键确认入库（选中建议确认的映射写入数据库）
       ↓
⑤ 采集公网满意度（根据已入库映射采集好评率等指标）
```

### 前置条件

- 宿主机已安装 **Google Chrome**。
- 宿主机已安装 **Node.js 18+**。
- 已运行 `start-windows.bat`（会自动启动 Chrome 和 CDP relay）。
- **在脚本打开的 Chrome 窗口中登录京东账号**（未登录会导致召回失败）。

### 验证 CDP relay 是否正常

在浏览器中打开（或 curl）：

```
http://localhost:9223/json/version
```

如果返回 JSON 格式的浏览器信息，说明 relay 正常。

### 各步骤说明

#### ① 召回候选商品

- 系统会根据内部商品的品牌+类目构造搜索关键词，在京东搜索框中逐个搜索。
- 每个关键词对应的前 `top-k`（默认 5）条搜索结果作为候选。
- **执行时间**：取决于候选商品数量，每个独立关键词约 15~30 秒。若多个商品共用同一关键词，只搜一次。
- 执行期间 Chrome 窗口会自动切换搜索词，属于正常现象，**不要手动操作浏览器**。

#### ② 计算映射分数

系统对每条候选记录从以下维度评分（满分约 0.9）：

| 维度 | 说明 |
|---|---|
| 品牌分 | 品牌名是否出现在候选标题中 |
| 类目分 | 类目关键词是否匹配 |
| 价格分 | 价格偏差是否在 10%/20% 以内 |
| 标题分 | 品牌+类目关键词在标题中的命中情况 |
| 证据分 | 候选字段的完整程度 |

评分 ≥ 0.60 → **建议确认**；0.40~0.60 → **建议复核**；< 0.40 → **建议拒绝**。

#### ③ 刷新评分预览

点击后可以查看当前评分结果，支持手动编辑每条记录的确认说明，选择要入库的记录（系统会按建议预选）。

#### ④ 一键确认入库

将选中的映射记录写入数据库，后续满意度采集以此为基础。

#### ⑤ 采集公网满意度

根据已入库的映射，在京东搜索页批量采集好评率、评价数、店铺评分等指标，并回写数据库。结果可在**商品分析**页查看。

## 公网采集手动操作说明

如果 `start-windows.bat` 没有自动启动 Chrome 或 relay，可以手动执行：

**启动 Chrome（远程调试模式）**：

```bat
"C:\Program Files\Google\Chrome\Application\chrome.exe" ^
  --remote-debugging-address=0.0.0.0 ^
  --remote-debugging-port=9222 ^
  --user-data-dir=C:\chrome-jd-profile ^
  https://www.jd.com
```

**启动 CDP relay**：

```bat
start-cdp-relay.bat
```

或：

```powershell
node scripts\cdp_relay.mjs
```

## 常见问题

**端口 80 被占用**

修改 `docker-compose.yml` 中前端端口，例如把 `"80:80"` 改成 `"8088:80"`，然后访问 `http://localhost:8088`。

**MySQL 端口被占用**

修改 `.env`：

```env
MYSQL_HOST_PORT=3308
```

重新启动：

```bash
docker compose up -d
```

**后端连不上数据库**

- 确认 `.env` 中 `MYSQL_HOST=mysql`（使用 Compose 内置 MySQL）。
- 查看 MySQL 是否健康：`docker compose ps`。
- 查看后端日志：`docker compose logs -f backend`。
- 首次初始化只会在 MySQL 数据卷为空时执行。如改过初始化 SQL，需先 `docker compose down -v` 清空卷再重启。

**Worker 不可达（采集/召回任务报错）**

- 确认 `public-task-worker` 容器在运行：`docker compose ps`。
- 确认 `.env` 中 `PUBLIC_TASK_WORKER_URL=http://public-task-worker:8090`。
- 查看日志：`docker compose logs -f public-task-worker`。

**召回/采集失败——浏览器连接失败**

- 确认 Chrome 已以远程调试模式启动（运行 `start-windows.bat` 会自动完成）。
- 确认 CDP relay 正在运行（控制台有 `CDP relay listening on 0.0.0.0:9223` 字样）。
- 检查 `http://localhost:9223/json/version` 是否返回 JSON。
- **确认已在 Chrome 中登录京东**，未登录会导致搜索时跳转登录页，召回结果为 0。
- 普通数据分析不需要 Chrome，可以先跳过公网采集功能。

**召回进度长时间卡在某个百分比**

这是正常现象。召回期间后端等待浏览器逐一完成搜索，进度条会随每个商品完成后逐步推进。可以在 Chrome 窗口中观察搜索是否在进行，如果搜索词在切换说明正在运行。

**镜像构建缓慢 / 依赖下载慢**

首次构建需要下载 Maven、Python、Node.js 依赖，视网络情况可能需要 5~20 分钟。后续无依赖变更时不带 `--build` 启动即可跳过构建。

## 手动开发模式（仅供开发调试）

不推荐给最终部署使用，需要自行准备 MySQL、Redis、JDK 17、Maven、Node.js 18+ 和 Python 3.11+。

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

本地访问：

- 前端：http://localhost:3000
- 后端：http://localhost:8080/api

## License

MIT License
