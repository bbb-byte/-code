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
# 一键启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
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
