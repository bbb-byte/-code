<template>
  <div class="data-manage">
    <div class="page-header">
      <h2 class="page-title">数据管理</h2>
      <p class="page-desc">archive 主数据、商品公网满意度补充与指定文件导入中心</p>
    </div>

    <el-row :gutter="24">
      <!-- 公网满意度补充 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">公网满意度补充</div>
          <div class="crawler-ui">
            <p class="desc">Python 爬虫当前只补充商品侧公开满意度指标，例如好评率、评论总数和店铺评分。它不抓取真实点击日志，也不会进入正式 `user_behavior` 主数据链路。</p>
            <p class="desc">这一步直接读取“公网映射文件”，也就是已经确认好的 `item_id -> 京东商品页` 关系；它不会直接读取 Kaggle/archive 原始行为文件。</p>
            <el-collapse class="flow-collapse">
              <el-collapse-item title="什么时候点这里" name="crawl-guide">
                <div class="flow-tips">
                  <ol>
                    <li>1. 先在下方“公网映射工作台”完成候选召回、映射评分和确认入库</li>
                    <li>2. 确认 `product_public_mapping` 表里已经有映射数据</li>
                    <li>3. 再点击这里，按映射文件抓取京东公开评价摘要</li>
                  </ol>
                </div>
              </el-collapse-item>
            </el-collapse>
            <div class="crawler-actions">
              <el-button type="warning" @click="handleCrawl" :loading="crawling" plain>
                <el-icon><Search /></el-icon> 采集公网满意度指标
              </el-button>
            </div>
            <div v-if="crawlResult" class="crawl-info">
              <el-alert
                title="公网满意度采集完成"
                :description="`来源: ${crawlResult.targetPlatform} | 指标文件: ${crawlResult.outputFile}`"
                type="success"
                show-icon
                :closable="false"
              />
              <p class="crawl-note mt-2">采集结果会自动尝试回写到商品公网满意度快照表，不会作为行为事件导入 `user_behavior`。</p>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 数据导入 -->
      <el-col :xs="24" :md="8">
        <div class="card">
            <div class="card-title">数据导入</div>
          <el-form :model="importForm" label-width="80px" label-position="left">
            <el-form-item label="文件路径">
              <el-input v-model="importForm.filePath" placeholder="支持 archive 主数据、legacy 7 列行为样本或带标准表头的行为文件" />
            </el-form-item>
            <div class="import-tips">
              这里只导入用户行为事件数据。公网满意度采集生成的指标文件会自动回写独立表，不需要再走这里的行为导入。
            </div>
            <el-form-item label="批量大小">
              <el-input-number v-model="importForm.batchSize" :min="1000" :max="10000" :step="1000" style="width: 100%" />
            </el-form-item>
            <el-form-item label="最大行数">
              <el-input-number v-model="importForm.maxRows" :min="0" :step="100000" style="width: 100%" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="handleImport" :loading="importing">
                <el-icon><Upload /></el-icon> 开始导入
              </el-button>
              <el-button @click="handleStopImport" :disabled="!importing">
                <el-icon><VideoPause /></el-icon> 停止
              </el-button>
            </el-form-item>
          </el-form>
          
          <div v-if="importing" class="import-progress">
            <div class="flex justify-between mb-2 text-sm text-gray-500">
              <span>导入进度</span>
              <span>{{ importProgress }}%</span>
            </div>
            <el-progress :percentage="importProgress" :stroke-width="8" :show-text="false" />
          </div>

          <div v-if="showImportSummary" class="import-summary">
            <div class="summary-title">导入摘要</div>
            <div class="summary-status">{{ importStatus.message || '暂无导入任务' }}</div>
            <div class="summary-grid">
              <div class="summary-item">
                <span class="label">成功入库</span>
                <span class="value">{{ formatCount(importStatus.insertedRows) }}</span>
              </div>
              <div class="summary-item">
                <span class="label">总跳过</span>
                <span class="value">{{ formatCount(importStatus.skippedRows) }}</span>
              </div>
              <div class="summary-item">
                <span class="label">文件内重复</span>
                <span class="value">{{ formatCount(importStatus.inFileDuplicateRows) }}</span>
              </div>
              <div class="summary-item">
                <span class="label">数据库重复</span>
                <span class="value">{{ formatCount(importStatus.dbDuplicateRows) }}</span>
              </div>
              <div class="summary-item">
                <span class="label">解析失败</span>
                <span class="value">{{ formatCount(importStatus.parseErrorRows) }}</span>
              </div>
              <div class="summary-item">
                <span class="label">行为类型异常</span>
                <span class="value">{{ formatCount(importStatus.unsupportedBehaviorRows) }}</span>
              </div>
              <div class="summary-item">
                <span class="label">自动预处理</span>
                <span class="value">{{ formatCount(importStatus.preprocessedRows) }}</span>
              </div>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 数据分析 -->
      <el-col :xs="24" :md="8">
        <div class="card">
          <div class="card-title">数据分析</div>
          <el-form :model="analyzeForm" label-width="90px" label-position="left">
            <el-form-item label="聚类簇数K">
              <el-input-number v-model="analyzeForm.clusterK" :min="2" :max="10" />
            </el-form-item>
            <el-form-item>
              <el-button type="success" @click="handleAnalyze" :loading="analyzing">
                <el-icon><DataAnalysis /></el-icon> 执行分析
              </el-button>
            </el-form-item>
            <div class="analyze-tips">
              <p class="font-medium">分析流程：</p>
              <ol>
                <li>1. 计算用户 RFM 指标</li>
                <li>2. 基于 RFM 进行用户分群</li>
                <li>3. 执行 K-Means 聚类分析</li>
              </ol>
            </div>
          </el-form>
        </div>
      </el-col>
    </el-row>

    <div class="card mapping-workbench">
      <div class="card-title">公网映射工作台</div>
      <p class="mapping-desc">
        这里用于管理员执行“原始行为文件/商品快照 -> 候选召回 -> 映射评分 -> 人工复核”流程。它只生成待核验文件，不会直接抓取公网满意度指标。
      </p>
      <el-collapse class="flow-collapse">
        <el-collapse-item title="从 Kaggle 数据集到补充用户满意度的推荐步骤" name="mapping-guide">
          <div class="flow-tips">
            <ol>
              <li>1. 在上方“数据导入”里先导入 `archive` / Kaggle 行为数据，完成主分析数据入库</li>
          <li>2. 在这里填写“原始行为文件”路径，例如 `archive/2019-Nov-demo.csv`；如果你已经有商品快照，也可以直接填写“商品快照”路径</li>
              <li>3. 点击“召回候选商品”，系统会先自动生成商品快照，再输出候选商品文件</li>
              <li>4. 点击“计算映射分数”，生成评分结果预览</li>
              <li>5. 在评分预览里检查候选商品，必要时点“编辑”修改标题、置信度、核验说明和证据备注</li>
              <li>6. 勾选通过的候选，点击“一键确认入库”，把结果写入 `product_public_mapping`</li>
              <li>7. 最后回到上方“公网满意度补充”，点击“采集公网满意度指标”，按已确认映射抓取京东公开评价摘要</li>
            </ol>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-form :model="mappingForm" label-width="110px" label-position="left">
        <el-row :gutter="24">
          <el-col :xs="24" :md="12">
            <el-form-item label="原始行为文件">
              <el-input v-model="mappingForm.sourceDataPath" placeholder="可选，Kaggle/archive 原始行为 CSV；填写后会先自动生成商品快照" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="商品快照">
              <el-input v-model="mappingForm.productPath" placeholder="内部商品快照 CSV 路径；若已提供原始行为文件，此项可作为回退值" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :xs="24" :md="12">
            <el-form-item label="自动快照输出">
              <el-input v-model="mappingForm.generatedProductPath" placeholder="自动生成的商品快照输出路径" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="夹具目录">
              <el-input v-model="mappingForm.fixtureDir" placeholder="可选，本地离线夹具目录" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :xs="24" :md="8">
            <el-form-item label="候选文件">
              <el-input v-model="mappingForm.candidateOutputPath" placeholder="候选召回输出 CSV" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="评分文件">
              <el-input v-model="mappingForm.scoreOutputPath" placeholder="评分结果输出 CSV" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="Top K">
              <el-input-number v-model="mappingForm.topK" :min="1" :max="20" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="mapping-actions">
          <el-button type="warning" plain @click="handleRecallCandidates" :loading="recallingCandidates">
            <el-icon><Search /></el-icon> 召回候选商品
          </el-button>
          <el-button type="primary" @click="handleScoreCandidates" :loading="scoringCandidates">
            <el-icon><DataAnalysis /></el-icon> 计算映射分数
          </el-button>
          <el-button @click="loadScorePreview" :loading="loadingScorePreview" plain>
            <el-icon><Refresh /></el-icon> 刷新评分预览
          </el-button>
        </div>
      </el-form>

      <div v-if="recallResult || scoreResult" class="mapping-result-grid">
        <el-alert
          v-if="recallResult"
          title="候选召回完成"
          :description="recallResultDescription"
          type="warning"
          show-icon
          :closable="false"
        />
        <el-alert
          v-if="scoreResult"
          title="映射评分完成"
          :description="`候选文件: ${scoreResult.candidateFile} | 评分文件: ${scoreResult.outputFile}`"
          type="success"
          show-icon
          :closable="false"
        />
      </div>
      <div v-if="confirmResult" class="mapping-result-grid">
        <el-alert
          title="映射确认入库完成"
          :description="`已入库 ${confirmResult.confirmedRows} 条 / 选中 ${confirmResult.requestedRows} 条`"
          type="success"
          show-icon
          :closable="false"
        />
      </div>
      <div v-if="mappingSchemaHint" class="mapping-result-grid">
        <el-alert
          title="公网映射表尚未初始化"
          :description="mappingSchemaHint"
          type="warning"
          show-icon
          :closable="false"
        />
      </div>
      <div v-if="currentPublicTask || lastStartedTaskId" class="mapping-task-card">
        <div class="mapping-preview-header">
          <div class="preview-title">公网任务进度</div>
          <el-tag
            :type="currentPublicTask
              ? (currentPublicTask.running ? 'warning' : (currentPublicTask.status === 'success' ? 'success' : 'danger'))
              : 'info'"
            size="small"
          >
            {{
              currentPublicTask
                ? (currentPublicTask.running ? '执行中' : (currentPublicTask.status === 'success' ? '已完成' : '失败'))
                : '已启动'
            }}
          </el-tag>
        </div>
        <div class="task-meta">
          <span>任务ID：{{ currentPublicTask?.taskId || lastStartedTaskId }}</span>
          <span v-if="currentPublicTask?.taskType">类型：{{ currentPublicTask.taskType }}</span>
        </div>
        <div class="task-message">
          {{ currentPublicTask?.message || '任务已启动，前端正在轮询进度；如果长时间不更新，请刷新页面并确认前后端已重启到最新版本。' }}
        </div>
        <el-progress
          :percentage="Math.max(0, Math.min(100, Number(currentPublicTask?.progress || 5)))"
          :status="currentPublicTask
            ? (currentPublicTask.running ? '' : (currentPublicTask.status === 'success' ? 'success' : 'exception'))
            : ''"
        />
      </div>
      <div class="mapping-preview">
        <div class="mapping-preview-header">
          <div class="preview-title">评分结果预览</div>
          <div class="preview-actions">
            <el-button size="small" plain @click="selectSuggestedRows" :disabled="!scorePreviewRows.length">
              按建议全选
            </el-button>
            <el-button
              size="small"
              type="success"
              @click="handleConfirmMappings"
              :loading="confirmingMappings"
              :disabled="!selectedScoreRows.length"
            >
              一键确认入库
            </el-button>
          </div>
        </div>
        <el-table
          ref="scoreTableRef"
          :data="scorePreviewRows"
          stripe
          v-loading="loadingScorePreview"
          empty-text="当前暂无评分结果，请先执行映射评分"
          @selection-change="handleScoreSelectionChange"
        >
          <el-table-column type="selection" width="48" />
          <el-table-column prop="itemId" label="商品ID" width="110" />
          <el-table-column prop="publicTitle" label="候选商品" min-width="220" />
          <el-table-column label="分数" width="90">
            <template #default="{ row }">
              {{ formatScore(row.totalScore) }}
            </template>
          </el-table-column>
          <el-table-column label="建议" width="110">
            <template #default="{ row }">
              <el-tag :type="actionTagType(row.recommendedAction)" size="small">
                {{ actionLabel(row.recommendedAction) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="scoreReason" label="依据" min-width="140" />
          <el-table-column label="价格对比" width="140">
            <template #default="{ row }">
              {{ formatPrice(row.internalPrice) }} / {{ formatPrice(row.publicPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="verificationNote" label="核验说明" min-width="180" show-overflow-tooltip />
          <el-table-column prop="evidenceNote" label="证据备注" min-width="180" show-overflow-tooltip />
          <el-table-column label="链接" width="100">
            <template #default="{ row }">
              <el-link v-if="row.sourceUrl" :href="row.sourceUrl" type="primary" target="_blank">
                打开商品页
              </el-link>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" link @click="openScoreEditDialog(row)">
                编辑
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="mapping-preview">
        <div class="mapping-preview-header">
          <div class="preview-title">已确认映射</div>
          <div class="preview-actions">
            <el-button size="small" plain @click="loadLatestPublicMappings" :loading="loadingConfirmedMappings">
              <el-icon><Refresh /></el-icon> 刷新映射列表
            </el-button>
          </div>
        </div>
        <el-table
          :data="confirmedMappingRows"
          stripe
          v-loading="loadingConfirmedMappings"
          empty-text="当前暂无已确认映射"
        >
          <el-table-column prop="itemId" label="商品ID" width="110" />
          <el-table-column prop="verifiedTitle" label="已确认商品" min-width="220" />
          <el-table-column prop="sourceProductId" label="公网ID" width="130" />
          <el-table-column label="置信度" width="90">
            <template #default="{ row }">
              {{ formatScore(row.mappingConfidence) }}
            </template>
          </el-table-column>
          <el-table-column prop="verificationNote" label="校验说明" min-width="220" />
          <el-table-column label="核验时间" width="180">
            <template #default="{ row }">
              {{ row.verifiedAt || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="链接" width="100">
            <template #default="{ row }">
              <el-link v-if="row.sourceUrl" :href="row.sourceUrl" type="primary" target="_blank">
                打开商品页
              </el-link>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button
                type="danger"
                size="small"
                link
                :loading="removingMappingId === row.id"
                @click="handleRemoveMapping(row)"
              >
                撤销
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <p class="mapping-note">
        建议只把高分候选作为人工复核起点；当前商品特征仍然较弱，不建议在页面里直接自动入库。
      </p>
    </div>

    <el-dialog
      v-model="scoreEditDialogVisible"
      title="编辑映射确认信息"
      width="640px"
      destroy-on-close
    >
      <el-form :model="scoreEditForm" label-width="110px" label-position="left">
        <el-form-item label="商品ID">
          <el-input :model-value="scoreEditForm.itemId" disabled />
        </el-form-item>
        <el-form-item label="公网标题">
          <el-input v-model="scoreEditForm.verifiedTitle" />
        </el-form-item>
        <el-form-item label="映射置信度">
          <el-input-number v-model="scoreEditForm.mappingConfidence" :min="0" :max="1" :step="0.01" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="核验说明">
          <el-input v-model="scoreEditForm.verificationNote" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="证据备注">
          <el-input v-model="scoreEditForm.evidenceNote" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scoreEditDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveScoreEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 数据概览 -->
    <div class="card">
      <div class="card-title">数据概览</div>
      <el-row :gutter="24">
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">用户总数</div>
            <div class="value">{{ overview.totalUsers.toLocaleString() }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">商品总数</div>
            <div class="value">{{ overview.totalProducts.toLocaleString() }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">类目总数</div>
            <div class="value">{{ overview.totalCategories.toLocaleString() }}</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="6">
          <div class="overview-item">
            <div class="label">行为记录</div>
            <div class="value">{{ overview.totalBehaviors.toLocaleString() }}</div>
          </div>
        </el-col>
      </el-row>
    </div>

    <!-- 最新入库数据 -->
    <div class="card">
      <div class="card-header flex justify-between items-center mb-4">
        <span class="card-title mb-0">最新入库数据预览</span>
        <el-button size="small" type="primary" link @click="loadLatestBehaviors">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
      <el-table :data="latestBehaviors" stripe style="width: 100%" v-loading="loadingLatest">
        <el-table-column prop="userId" label="用户ID" width="120" />
        <el-table-column prop="itemId" label="商品ID" width="120" />
        <el-table-column prop="categoryId" label="类目ID" width="140">
          <template #default="scope">
            <el-tag effect="plain" type="info">{{ scope.row.categoryId }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="behaviorType" label="行为类型" width="120">
          <template #default="scope">
            <el-tag :type="getTagType(scope.row.behaviorType)" effect="light">{{ scope.row.behaviorType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="120">
          <template #default="scope">
            {{ formatPrice(scope.row.unitPrice) }}
          </template>
        </el-table-column>
        <el-table-column prop="behaviorDateTime" label="时间" />
      </el-table>
    </div>

    <!-- 操作日志 -->
    <div class="card">
      <div class="card-title">操作日志</div>
      <el-timeline>
        <el-timeline-item
          v-for="log in logs"
          :key="log.id"
          :timestamp="log.time"
          :type="log.type"
          :hollow="true"
        >
          {{ log.message }}
        </el-timeline-item>
      </el-timeline>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, reactive, onMounted, onUnmounted } from 'vue'
import {
  importData,
  getImportProgress,
  stopImport,
  analyzeData,
  crawlData,
  getLatestBehaviors,
  recallPublicMappingCandidates,
  scorePublicMappingCandidates,
  previewPublicMappingScores,
  confirmPublicMappings,
  getLatestPublicMappings,
  removePublicMapping,
  getPublicTaskProgress
} from '@/api/data'
import { getOverview } from '@/api/analysis'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, VideoPause, DataAnalysis, Search, Refresh } from '@element-plus/icons-vue'

const importing = ref(false)
const analyzing = ref(false)
const crawling = ref(false)
const recallingCandidates = ref(false)
const scoringCandidates = ref(false)
const loadingScorePreview = ref(false)
const confirmingMappings = ref(false)
const loadingConfirmedMappings = ref(false)
const loadingLatest = ref(false)
const removingMappingId = ref(null)
const importProgress = ref(0)
const importStatus = ref(createEmptyImportStatus())
const crawlResult = ref(null)
const recallResult = ref(null)
const scoreResult = ref(null)
const confirmResult = ref(null)
const latestBehaviors = ref([])
const scorePreviewRows = ref([])
const selectedScoreRows = ref([])
const scoreTableRef = ref(null)
const confirmedMappingRows = ref([])
const mappingSchemaHint = ref('')
const currentPublicTask = ref(null)
const lastStartedTaskId = ref('')
const scoreEditDialogVisible = ref(false)
const editingScoreRow = ref(null)

const importForm = reactive({
  filePath: '/Users/leiminghao/Desktop/论文code/-code/archive/2019-Nov-demo.csv',
  batchSize: 5000,
  maxRows: 100000
})

const analyzeForm = reactive({
  clusterK: 5
})

const mappingForm = reactive({
  sourceDataPath: '',
  productPath: 'crawler/mappings/internal_products.sample.csv',
  generatedProductPath: 'crawler/output/internal_products.auto.csv',
  fixtureDir: 'crawler/fixtures',
  topK: 5,
  candidateOutputPath: 'crawler/output/recalled_candidates.csv',
  scoreOutputPath: 'crawler/output/recalled_candidate_scores.csv'
})

const scoreEditForm = reactive({
  itemId: '',
  verifiedTitle: '',
  mappingConfidence: 0.5,
  verificationNote: '',
  evidenceNote: ''
})

const overview = ref({
  totalUsers: 0,
  totalProducts: 0,
  totalCategories: 0,
  totalBehaviors: 0
})

const logs = ref([
  { id: 1, time: new Date().toLocaleString(), message: '系统初始化完成', type: 'success' }
])

let progressTimer = null
let publicTaskTimer = null

function createEmptyImportStatus() {
  return {
    importing: false,
    progress: 0,
    totalRows: 0,
    processedRows: 0,
    insertedRows: 0,
    skippedRows: 0,
    inFileDuplicateRows: 0,
    dbDuplicateRows: 0,
    parseErrorRows: 0,
    unsupportedBehaviorRows: 0,
    preprocessedRows: 0,
    defaultedPriceRows: 0,
    defaultedQtyRows: 0,
    filePath: '',
    format: '',
    message: '',
    finishedAt: null
  }
}

const handleCrawl = async () => {
  crawling.value = true
  addLog('开始采集商品公网满意度指标任务...', 'warning')
  
  try {
    const res = await crawlData(
      'crawler/mappings/product_public_mapping.jd.sample.csv',
      'crawler/output',
      'crawler/fixtures'
    )
    lastStartedTaskId.value = res.data.taskId || ''
    currentPublicTask.value = {
      taskId: res.data.taskId,
      taskType: 'crawl',
      running: true,
      progress: 1,
      status: 'running',
      message: '公网满意度采集任务已启动'
    }
    addLog(`公网满意度采集任务已启动，任务ID: ${res.data.taskId}`, 'primary')
    pollPublicTask(res.data.taskId, {
      onSuccess: (task) => {
        crawlResult.value = task.result || {}
        const imported = task.result?.importedRows ?? '部分'
        addLog(`✨ 公网满意度采集完成！输出 ${imported} 条商品指标，并已尝试回写数据库。`, 'success')
        ElMessage.success(`成功采集 ${imported} 条公网商品指标`)
      },
      onFailed: (task) => {
        addLog('采集失败: ' + (task.message || '任务执行失败'), 'danger')
      },
      onFinally: () => {
        crawling.value = false
      }
    })
  } catch (error) {
    addLog('采集失败: ' + (error.response?.data?.message || error.message), 'danger')
    crawling.value = false
  }
}

const handleRecallCandidates = async () => {
  recallingCandidates.value = true
  addLog('开始召回公网映射候选商品...', 'warning')

  try {
    const res = await recallPublicMappingCandidates(
      mappingForm.productPath,
      mappingForm.candidateOutputPath,
      mappingForm.fixtureDir,
      mappingForm.sourceDataPath,
      mappingForm.generatedProductPath,
      mappingForm.topK
    )
    lastStartedTaskId.value = res.data.taskId || ''
    currentPublicTask.value = {
      taskId: res.data.taskId,
      taskType: 'recall',
      running: true,
      progress: 1,
      status: 'running',
      message: '公网映射候选召回任务已启动'
    }
    addLog(`候选召回任务已启动，任务ID: ${res.data.taskId}`, 'primary')
    pollPublicTask(res.data.taskId, {
      onSuccess: (task) => {
        recallResult.value = task.result || {}
        if (task.result?.generatedProductPath) {
          mappingForm.productPath = task.result.generatedProductPath
        } else if (task.result?.productPath) {
          mappingForm.productPath = task.result.productPath
        }
        if (task.result?.outputFile) {
          mappingForm.candidateOutputPath = task.result.outputFile
        }
        scoreResult.value = null
        confirmResult.value = null
        scorePreviewRows.value = []
        selectedScoreRows.value = []
        addLog(`候选召回完成：已生成候选文件 ${task.result?.outputFile}`, 'success')
        ElMessage.success('候选召回完成，请继续执行映射评分')
      },
      onFailed: (task) => {
        addLog('候选召回失败: ' + (task.message || '任务执行失败'), 'danger')
      },
      onFinally: () => {
        recallingCandidates.value = false
      }
    })
  } catch (error) {
    addLog('候选召回失败: ' + (error.response?.data?.message || error.message), 'danger')
    recallingCandidates.value = false
  }
}

const handleScoreCandidates = async () => {
  scoringCandidates.value = true
  addLog('开始计算公网映射候选分数...', 'primary')

  try {
    const res = await scorePublicMappingCandidates(
      mappingForm.productPath,
      mappingForm.candidateOutputPath,
      mappingForm.scoreOutputPath
    )
    lastStartedTaskId.value = res.data.taskId || ''
    currentPublicTask.value = {
      taskId: res.data.taskId,
      taskType: 'score',
      running: true,
      progress: 1,
      status: 'running',
      message: '公网映射评分任务已启动'
    }
    addLog(`映射评分任务已启动，任务ID: ${res.data.taskId}`, 'primary')
    pollPublicTask(res.data.taskId, {
      onSuccess: async (task) => {
        scoreResult.value = task.result || {}
        await loadScorePreview()
        addLog(`映射评分完成：已生成评分文件 ${task.result?.outputFile}`, 'success')
        ElMessage.success('映射评分完成')
      },
      onFailed: (task) => {
        addLog('映射评分失败: ' + (task.message || '任务执行失败'), 'danger')
      },
      onFinally: () => {
        scoringCandidates.value = false
      }
    })
  } catch (error) {
    addLog('映射评分失败: ' + (error.response?.data?.message || error.message), 'danger')
    scoringCandidates.value = false
  }
}

const clearPublicTaskTimer = () => {
  if (publicTaskTimer) {
    clearInterval(publicTaskTimer)
    publicTaskTimer = null
  }
}

const pollPublicTask = (taskId, handlers = {}) => {
  clearPublicTaskTimer()
  const tick = async () => {
    try {
      const res = await getPublicTaskProgress(taskId)
      const task = res.data
      currentPublicTask.value = task
      if (task?.running) {
        return
      }
      clearPublicTaskTimer()
      if (task?.status === 'success') {
        if (handlers.onSuccess) await handlers.onSuccess(task)
      } else {
        if (handlers.onFailed) handlers.onFailed(task)
      }
      if (handlers.onFinally) handlers.onFinally(task)
    } catch (error) {
      clearPublicTaskTimer()
      if (handlers.onFailed) handlers.onFailed({ message: error.response?.data?.message || error.message })
      if (handlers.onFinally) handlers.onFinally()
    }
  }
  publicTaskTimer = setInterval(tick, 2000)
  tick()
}

const loadScorePreview = async () => {
  loadingScorePreview.value = true
  try {
    const res = await previewPublicMappingScores(mappingForm.scoreOutputPath)
    scorePreviewRows.value = res.data || []
    selectedScoreRows.value = []
    await nextTick()
    selectSuggestedRows()
  } catch (error) {
    scorePreviewRows.value = []
    addLog('评分结果预览加载失败: ' + (error.response?.data?.message || error.message), 'danger')
  } finally {
    loadingScorePreview.value = false
  }
}

const handleScoreSelectionChange = (rows) => {
  selectedScoreRows.value = rows
}

const selectSuggestedRows = () => {
  const table = scoreTableRef.value
  if (!table) return
  table.clearSelection()
  scorePreviewRows.value
    .filter(row => row.recommendedAction && row.recommendedAction !== 'reject')
    .forEach(row => table.toggleRowSelection(row, true))
}

const handleConfirmMappings = async () => {
  if (!selectedScoreRows.value.length) {
    ElMessage.warning('请先选择要确认入库的评分结果')
    return
  }

  confirmingMappings.value = true
  addLog(`开始确认 ${selectedScoreRows.value.length} 条公网映射并入库...`, 'warning')

  try {
    const res = await confirmPublicMappings(selectedScoreRows.value)
    confirmResult.value = res.data
    await loadLatestPublicMappings()
    addLog(`公网映射确认完成：已入库 ${res.data.confirmedRows} 条`, 'success')
    ElMessage.success(`已确认入库 ${res.data.confirmedRows} 条映射`)
  } catch (error) {
    addLog('公网映射确认失败: ' + (error.response?.data?.message || error.message), 'danger')
  } finally {
    confirmingMappings.value = false
  }
}

const openScoreEditDialog = (row) => {
  editingScoreRow.value = row
  scoreEditForm.itemId = row.itemId || ''
  scoreEditForm.verifiedTitle = row.verifiedTitle || row.publicTitle || ''
  scoreEditForm.mappingConfidence = Number(row.mappingConfidence ?? row.totalScore ?? 0.5)
  scoreEditForm.verificationNote = row.verificationNote || ''
  scoreEditForm.evidenceNote = row.evidenceNote || ''
  scoreEditDialogVisible.value = true
}

const saveScoreEdit = () => {
  if (!editingScoreRow.value) {
    scoreEditDialogVisible.value = false
    return
  }
  editingScoreRow.value.verifiedTitle = scoreEditForm.verifiedTitle
  editingScoreRow.value.mappingConfidence = Number(scoreEditForm.mappingConfidence)
  editingScoreRow.value.verificationNote = scoreEditForm.verificationNote
  editingScoreRow.value.evidenceNote = scoreEditForm.evidenceNote
  scoreEditDialogVisible.value = false
  addLog(`已更新商品 ${editingScoreRow.value.itemId} 的确认说明`, 'primary')
}

const loadLatestPublicMappings = async () => {
  loadingConfirmedMappings.value = true
  try {
    const res = await getLatestPublicMappings('jd', 10)
    confirmedMappingRows.value = res.data || []
    mappingSchemaHint.value = (res.message && res.message.includes('upgrade_archive_dataset.sql')) ? res.message : ''
  } catch (error) {
    confirmedMappingRows.value = []
    mappingSchemaHint.value = error.response?.data?.message || ''
    addLog('已确认映射列表加载失败: ' + (error.response?.data?.message || error.message), 'danger')
  } finally {
    loadingConfirmedMappings.value = false
  }
}

const handleRemoveMapping = async (row) => {
  if (!row?.id) return

  try {
    await ElMessageBox.confirm(
      `将撤销商品 ${row.itemId} 的公网映射，并清理对应满意度快照。是否继续？`,
      '撤销公网映射',
      {
        confirmButtonText: '确认撤销',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  removingMappingId.value = row.id
  addLog(`开始撤销商品 ${row.itemId} 的公网映射...`, 'warning')
  try {
    await removePublicMapping(row.id)
    addLog(`公网映射已撤销：商品 ${row.itemId}`, 'success')
    ElMessage.success('公网映射已撤销')
    await loadLatestPublicMappings()
  } catch (error) {
    addLog('撤销公网映射失败: ' + (error.response?.data?.message || error.message), 'danger')
  } finally {
    removingMappingId.value = null
  }
}

const formatScore = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(2) : value
}

const actionTagType = (action) => {
  if (action === 'fast_review') return 'success'
  if (action === 'manual_review') return 'warning'
  return 'info'
}

const actionLabel = (action) => {
  if (action === 'fast_review') return '建议确认'
  if (action === 'manual_review') return '建议复核'
  return '建议拒绝'
}

const handleImport = async () => {
  if (!importForm.filePath) {
    ElMessage.warning('请输入数据文件路径')
    return
  }
  
  if (progressTimer) clearInterval(progressTimer)
  importing.value = true
  importProgress.value = 0
  importStatus.value = {
    ...createEmptyImportStatus(),
    importing: true,
    filePath: importForm.filePath,
    message: '导入任务已启动'
  }
  addLog(`开始从路径读取数据: ${importForm.filePath}（自动预处理已启用）`, 'primary')
  
  try {
    await importData(importForm.filePath, importForm.batchSize, importForm.maxRows)
    ElMessage.success('导入任务已启动')
    
    progressTimer = setInterval(async () => {
      try {
        const res = await getImportProgress()
        importStatus.value = {
          ...createEmptyImportStatus(),
          ...res.data
        }
        importProgress.value = Math.min(importStatus.value.progress || 0, 100)
        
        if (!importStatus.value.importing) {
          clearInterval(progressTimer)
          progressTimer = null
          importing.value = false
          if (!importStatus.value.totalRows) {
            importProgress.value = importStatus.value.insertedRows > 0 ? 100 : 0
          }
          addLog(
            `导入结束：成功 ${formatCount(importStatus.value.insertedRows)} 条，跳过 ${formatCount(importStatus.value.skippedRows)} 条，解析失败 ${formatCount(importStatus.value.parseErrorRows)} 条，自动预处理 ${formatCount(importStatus.value.preprocessedRows)} 条。`,
            isImportOutcomeError(importStatus.value) ? 'danger' : (importStatus.value.insertedRows > 0 ? 'success' : 'warning')
          )
          if (isImportOutcomeError(importStatus.value)) {
            ElMessage.error(importStatus.value.message || '数据导入失败')
          } else if (importStatus.value.insertedRows > 0) {
            ElMessage.success(importStatus.value.message || '数据导入完成')
          } else {
            ElMessage.warning(importStatus.value.message || '数据导入结束')
          }
          loadOverview() 
          loadLatestBehaviors() 
        }
      } catch (e) {
        console.error(e)
      }
    }, 2000)
  } catch (error) {
    importing.value = false
    addLog('❌ 导入失败: ' + (error.response?.data?.message || error.message), 'danger')
  }
}

const handleStopImport = async () => {
  try {
    await stopImport()
    ElMessage.info('正在停止导入')
    addLog('用户停止导入', 'warning')
  } catch (error) {
    console.error(error)
  }
}

const handleAnalyze = async () => {
  analyzing.value = true
  addLog('开始执行数据分析...', 'primary')
  
  try {
    await analyzeData(analyzeForm.clusterK)
    ElMessage.success('数据分析完成')
    addLog('数据分析完成', 'success')
  } catch (error) {
    addLog('分析失败: ' + error.message, 'danger')
  } finally {
    analyzing.value = false
  }
}

const loadOverview = async () => {
  try {
    const res = await getOverview()
    overview.value = res.data
  } catch (error) {
    console.error(error)
  }
}

const loadLatestBehaviors = async () => {
  loadingLatest.value = true
  try {
    const res = await getLatestBehaviors(10)
    latestBehaviors.value = res.data
  } catch (error) {
    console.error(error)
  } finally {
    loadingLatest.value = false
  }
}

const getTagType = (type) => {
  const map = {
    'pv': 'info',
    'buy': 'success',
    'cart': 'warning',
    'fav': 'danger'
  }
  return map[type] || 'info'
}

const formatPrice = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  const num = Number(value)
  return Number.isFinite(num) ? `¥${num.toFixed(2)}` : value
}

const formatCount = (value) => Number(value || 0).toLocaleString()

const showImportSummary = computed(() => importing.value || importStatus.value.finishedAt || importStatus.value.insertedRows > 0 || importStatus.value.skippedRows > 0)
const recallResultDescription = computed(() => {
  if (!recallResult.value) return ''
  const snapshotPath = recallResult.value.generatedProductPath || recallResult.value.productPath || '-'
  const candidatePath = recallResult.value.outputFile || '-'
  return `商品快照: ${snapshotPath} | 候选文件: ${candidatePath} | 下一步请执行“计算映射分数”`
})

const isImportOutcomeError = (status) => /失败|不支持|为空/.test(status?.message || '')

const addLog = (message, type = 'info') => {
  logs.value.unshift({
    id: Date.now(),
    time: new Date().toLocaleString(),
    message,
    type
  })
  if (logs.value.length > 20) logs.value.pop()
}

onMounted(() => {
  loadOverview()
  loadLatestBehaviors()
  loadLatestPublicMappings()
})

onUnmounted(() => {
  if (progressTimer) clearInterval(progressTimer)
  clearPublicTaskTimer()
})
</script>

<style scoped lang="scss">
.data-manage {
  .el-col { margin-bottom: 24px; }

  .crawler-ui {
    .desc {
      font-size: 14px;
      color: var(--text-secondary);
      margin-bottom: 16px;
      line-height: 1.6;
    }
    .crawler-actions {
      display: flex;
      justify-content: center;
      margin-bottom: 16px;
    }
    .crawl-info {
      background: var(--bg-color-page);
      border-radius: 8px;
      padding: 12px;
    }

    .crawl-note {
      font-size: 12px;
      color: var(--text-secondary);
      line-height: 1.6;
    }
  }

  .flow-tips {
    margin: 0;
    padding: 14px 16px;
    background: rgba(245, 158, 11, 0.08);
    border: 1px solid rgba(245, 158, 11, 0.18);
    border-radius: 8px;

    .tips-title {
      font-weight: 600;
      color: #b45309;
      margin-bottom: 8px;
    }

    ol {
      margin: 0;
      padding-left: 0;
      list-style: none;

      li {
        color: #92400e;
        font-size: 13px;
        line-height: 1.7;
        margin-bottom: 4px;
      }
    }
  }

  .flow-collapse {
    margin: 0 0 16px;
    border: none;

    :deep(.el-collapse-item__header) {
      font-size: 13px;
      color: var(--text-secondary);
      border-bottom: none;
      background: transparent;
      height: auto;
      line-height: 1.6;
      padding: 0;
    }

    :deep(.el-collapse-item__wrap) {
      border-bottom: none;
      background: transparent;
    }

    :deep(.el-collapse-item__content) {
      padding-bottom: 0;
      padding-top: 8px;
    }
  }
  
  .import-progress {
    margin-top: 24px;
    padding: 16px;
    background: var(--bg-color-page);
    border-radius: 8px;
  }

  .import-tips {
    margin: -4px 0 16px;
    font-size: 13px;
    line-height: 1.6;
    color: var(--text-secondary);
  }

  .import-summary {
    margin-top: 16px;
    padding: 16px;
    background: var(--bg-color-page);
    border-radius: 8px;
    border: 1px solid var(--border-light);

    .summary-title {
      font-weight: 600;
      color: var(--text-primary);
      margin-bottom: 6px;
    }

    .summary-status {
      font-size: 13px;
      color: var(--text-secondary);
      margin-bottom: 12px;
      line-height: 1.5;
    }

    .summary-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px 16px;
    }

    .summary-item {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      font-size: 13px;
    }

    .label {
      color: var(--text-secondary);
    }

    .value {
      color: var(--text-primary);
      font-weight: 600;
    }
  }
  
  .analyze-tips {
    margin-top: 20px;
    background: rgba(16, 185, 129, 0.05);
    border: 1px solid rgba(16, 185, 129, 0.2);
    padding: 16px;
    border-radius: 8px;
    
    p { 
      color: #059669;
      margin-bottom: 8px;
    }
    ol { 
      margin: 0; 
      padding-left: 0;
      list-style: none;
      
      li {
        color: #059669;
        font-size: 13px;
        margin-bottom: 4px;
      }
    }
  }
  
  .overview-item {
    text-align: center;
    padding: 20px;
    background: var(--bg-color-page);
    border-radius: 8px;
    border: 1px solid var(--border-light);
    
    .label {
      color: var(--text-secondary);
      font-size: 14px;
      margin-bottom: 8px;
    }
    
    .value {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-primary);
    }
  }

  .mapping-workbench {
    .mapping-desc,
    .mapping-note {
      font-size: 13px;
      line-height: 1.7;
      color: var(--text-secondary);
    }

    .mapping-desc {
      margin: -4px 0 16px;
    }

    .mapping-actions {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      margin-top: 4px;
    }

    .mapping-result-grid {
      display: grid;
      gap: 12px;
      margin-top: 16px;
    }

    .mapping-note {
      margin-top: 12px;
    }

    .mapping-preview {
      margin-top: 16px;
    }

    .mapping-task-card {
      margin-top: 16px;
      padding: 16px;
      border: 1px solid var(--border-light);
      border-radius: 10px;
      background: var(--bg-color-page);
    }

    .task-meta {
      display: flex;
      gap: 16px;
      flex-wrap: wrap;
      font-size: 12px;
      color: var(--text-secondary);
      margin-bottom: 8px;
    }

    .task-message {
      font-size: 13px;
      color: var(--text-primary);
      margin-bottom: 12px;
      line-height: 1.6;
    }

    .mapping-preview-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;
      flex-wrap: wrap;
    }

    .preview-title {
      font-weight: 600;
      color: var(--text-primary);
    }

    .preview-actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
  }
  
  .mt-2 { margin-top: 8px; }
}
</style>
