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
            <p class="desc">这一步直接读取"公网映射文件"，也就是已经确认好的 `item_id -> 京东商品页` 关系；它不会直接读取 Kaggle/archive 原始行为文件。</p>
            <el-collapse class="flow-collapse">
              <el-collapse-item title="什么时候点这里" name="crawl-guide">
                <div class="flow-tips">
                  <ol>
                    <li>1. 先在下方"公网映射工作台"完成候选召回、映射评分和确认入库</li>
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
              <el-button type="success" @click="handleAttachedSearchCrawl" :loading="crawling" plain>
                <el-icon><Search /></el-icon> 附着当前搜索页采集
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
            <el-form-item label="上传文件">
              <el-upload
                ref="importUploadRef"
                class="w-full"
                :auto-upload="false"
                :show-file-list="true"
                :limit="1"
                accept=".csv,text/csv"
                :on-change="handleImportFileChange"
                :on-remove="handleImportFileRemove"
              >
                <el-button type="primary" plain>选择 CSV 文件</el-button>
                <template #tip>
                  <div class="el-upload__tip">
                    示例文件名：<code>2020-Apr.csv</code>、<code>2020-Apr-demo.csv</code><br>
                    支持 archive 主数据（含表头）、legacy 7 列行为样本，系统自动识别格式并预处理。
                  </div>
                </template>
              </el-upload>
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
            <div v-if="currentAnalyzeTask" class="analyze-task-tip">
              <el-progress
                :percentage="Math.max(0, Math.min(100, Number(currentAnalyzeTask?.progress || 5)))"
                :status="currentAnalyzeTask?.running ? '' : (currentAnalyzeTask?.status === 'success' ? 'success' : 'exception')"
              />
              <div class="analyze-task-text">
                {{ currentAnalyzeTask?.message || '分析任务状态同步中...' }}
              </div>
            </div>
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
        这里用于管理员执行"原始行为文件/商品快照 -> 候选召回 -> 映射评分 -> 人工复核"流程。它只生成待核验文件，不会直接抓取公网满意度指标。
      </p>
      <el-collapse class="flow-collapse">
        <el-collapse-item title="从 Kaggle 数据集到补充用户满意度的推荐步骤" name="mapping-guide">
          <div class="flow-tips">
            <ol>
              <li>1. 在上方"数据导入"里先导入 `archive` / Kaggle 行为数据，完成主分析数据入库</li>
              <li>2. 在这里上传"原始行为文件"，例如 Kaggle/archive 原始行为 CSV；如果你已经有商品快照，也可以直接上传"商品快照"文件</li>
              <li>3. 当前页面不会自动在 Windows 宿主机启动 Chrome。前端运行在浏览器沙箱里，后端运行在 Docker 容器里，都只能连接一个已经存在的 CDP 地址，不能直接在宿主机替你创建 `chrome.exe` 进程。请先在宿主机用以下命令打开带远程调试端口的 Chrome，并手动登录京东：<br>
                <code>chrome.exe --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222 --user-data-dir=C:\chrome-jd-profile</code><br>
                然后在另一个命令行窗口运行 <code>python scripts\cdp_relay.py</code>，或者直接双击项目根目录下的 <code>start-cdp-relay.bat</code>。登录后不要关闭这两个窗口，然后在下方"宿主机浏览器地址"填入 <code>http://host.docker.internal:9223</code>
              </li>
              <li>4. 当前机器上 Chrome 的 DevTools 端口通常只对宿主机本地的 `127.0.0.1:9222` 可见，所以页面默认改为复用 relay 转发出来的 <code>http://host.docker.internal:9223</code>；这个 9223 会再转发到本机的 `127.0.0.1:9222`</li>
              <li>5. 点击"召回候选商品"，系统会接管你已登录的 Chrome 进行搜索，再输出候选商品文件</li>
              <li>6. 点击"计算映射分数"，生成评分结果预览</li>
              <li>7. 在评分预览里检查候选商品，必要时点"编辑"修改标题、置信度、核验说明和证据备注</li>
              <li>8. 勾选通过的候选，点击"一键确认入库"，把结果写入 `product_public_mapping`</li>
              <li>9. 最后回到上方"公网满意度补充"，点击"采集公网满意度指标"，按已确认映射抓取京东公开评价摘要</li>
            </ol>
          </div>
        </el-collapse-item>
      </el-collapse>
      <el-form :model="mappingForm" label-width="110px" label-position="left">
        <el-row :gutter="24">
          <el-col :xs="24" :md="12">
            <el-form-item label="原始行为文件">
              <el-upload
                class="w-full"
                :auto-upload="false"
                :show-file-list="true"
                :limit="1"
                accept=".csv,text/csv"
                :on-change="(f) => handleMappingFileChange('source', f)"
                :on-remove="() => handleMappingFileRemove('source')"
              >
                <el-button plain size="small">选择 CSV 文件</el-button>
                <template #tip>
                  <div class="el-upload__tip">
                    示例文件名：<code>2020-Apr.csv</code>、<code>2020-Apr-demo.csv</code><br>
                    上传后系统会自动从中提取商品列表，生成商品快照，再输出候选文件。
                  </div>
                </template>
              </el-upload>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="商品快照">
              <el-upload
                class="w-full"
                :auto-upload="false"
                :show-file-list="true"
                :limit="1"
                accept=".csv,text/csv"
                :on-change="(f) => handleMappingFileChange('product', f)"
                :on-remove="() => handleMappingFileRemove('product')"
              >
                <el-button plain size="small">选择 CSV 文件</el-button>
                <template #tip>
                  <div class="el-upload__tip">
                    示例文件名：<code>internal_products.auto.csv</code><br>
                    需包含 <code>item_id</code>、<code>category_id</code>、<code>price</code> 列；若同时上传了原始行为文件，此项可留空。
                  </div>
                </template>
              </el-upload>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :xs="24" :md="12">
            <el-form-item label="自动快照输出">
              <el-input v-model="mappingForm.generatedProductPath" placeholder="例：crawler/output/internal_products.auto.csv" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="12">
            <el-form-item label="夹具目录">
              <el-input v-model="mappingForm.fixtureDir" placeholder="可选，例：crawler/fixtures/jd_search_html" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :xs="24" :md="8">
            <el-form-item label="候选文件">
              <el-input v-model="mappingForm.candidateOutputPath" placeholder="例：crawler/output/recalled_candidates.browser.csv" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="评分文件">
              <el-input v-model="mappingForm.scoreOutputPath" placeholder="例：crawler/output/recalled_candidate_scores.csv" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="Top K">
              <el-input-number v-model="mappingForm.topK" :min="1" :max="20" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="最大商品数">
              <el-input-number v-model="mappingForm.maxProducts" :min="0" :max="5000" :step="50" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24" style="margin-top: 4px;">
          <el-col :xs="24" :md="16">
            <el-form-item label="宿主机浏览器">
              <el-input
                v-model="mappingForm.cdpUrl"
                placeholder="Docker 容器内推荐填写 http://host.docker.internal:9223"
                clearable
              >
                <template #prepend>CDP 地址</template>
              </el-input>
              <el-collapse style="margin-top: 8px;">
                <el-collapse-item title="为什么这里不会自动启动宿主机 Chrome？">
                  <div class="el-upload__tip">
                    前端页面没有权限直接启动本机 `chrome.exe`，而后端运行在 Docker 容器中，也只能连接已经存在的 CDP 端口。
                    当前推荐方式是：Chrome 继续监听 `127.0.0.1:9222`，再由宿主机上的 `python scripts\cdp_relay.py` 把 `9223` 转发到 `9222`，页面默认复用 `http://host.docker.internal:9223`。
                  </div>
                </el-collapse-item>
              </el-collapse>
              <div class="el-upload__tip" style="margin-top:4px;">
                当前召回会优先复用宿主机已登录浏览器，在京东页面搜索框中逐字输入关键词并提交。推荐顺序：先运行 `chrome.exe --remote-debugging-address=0.0.0.0 --remote-debugging-port=9222 --user-data-dir=C:\chrome-jd-profile`，再运行 `python scripts\cdp_relay.py` 或双击 `start-cdp-relay.bat`，最后在这里使用 `http://host.docker.internal:9223`
              </div>
            </el-form-item>
          </el-col>
        </el-row>
        <div class="mapping-actions">
          <el-button type="warning" plain @click="handleRecallCandidates" :loading="recallingCandidates">
            <el-icon><Search /></el-icon> 召回候选商品
          </el-button>
          <el-button type="primary" plain @click="handleScoreCandidates" :loading="scoringCandidates">
            <el-icon><DataAnalysis /></el-icon> 计算映射分数
          </el-button>
          <el-button @click="loadScorePreview" :loading="loadingScorePreview" plain>
            <el-icon><Refresh /></el-icon> 刷新评分预览
          </el-button>
        </div>
        <div v-if="mappingForm.fixtureDir" class="mapping-note">
          当前填写了夹具目录，召回会优先读取该目录下按商品拆分的离线页面，例如 `jd_search_1001588.html`。如果目录里只有通用 sample 文件，结果只适合演示，不适合批量确认入库。
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
        <div style="display: flex; align-items: center; gap: 12px;">
          <el-progress
            :percentage="Math.max(0, Math.min(100, Number(currentPublicTask?.progress || 5)))"
            :status="currentPublicTask
              ? (currentPublicTask.running ? '' : (currentPublicTask.status === 'success' ? 'success' : 'exception'))
              : ''"
            style="flex: 1;"
          />
          <el-button
            v-if="currentPublicTask?.running"
            type="danger"
            size="small"
            plain
            @click="handleCancelTask"
            :loading="cancellingTask"
          >
            <el-icon><VideoPause /></el-icon> 取消
          </el-button>
        </div>
      </div>
      <div class="mapping-preview">
        <div class="mapping-preview-header">
          <div class="preview-title">评分结果预览</div>
          <div class="preview-actions">
            <span class="preview-page-meta" v-if="scorePreviewTotal">
              共 {{ scorePreviewTotal }} 条，当前第 {{ scorePreviewPage }} / {{ Math.max(1, Math.ceil(scorePreviewTotal / scorePreviewPageSize)) }} 页
            </span>
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
        <div class="pagination-wrap" v-if="scorePreviewTotal > 0">
          <el-pagination
            background
            layout="total, prev, pager, next, sizes"
            :current-page="scorePreviewPage"
            :page-size="scorePreviewPageSize"
            :page-sizes="[20, 50, 100, 200]"
            :total="scorePreviewTotal"
            @current-change="handleScorePreviewPageChange"
            @size-change="handleScorePreviewSizeChange"
          />
        </div>
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
  uploadImportFile,
  uploadMappingFile,
  getImportProgress,
  stopImport,
  startAnalyzeTask,
  crawlData,
  crawlAttachedSearchData,
  getLatestBehaviors,
  recallPublicMappingCandidates,
  scorePublicMappingCandidates,
  previewPublicMappingScores,
  confirmPublicMappings,
  getLatestPublicMappings,
  removePublicMapping,
  getPublicTaskProgress,
  cancelPublicTask
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
const cancellingTask = ref(false)
const importProgress = ref(0)
const importStatus = ref(createEmptyImportStatus())
const crawlResult = ref(null)
const recallResult = ref(null)
const scoreResult = ref(null)
const confirmResult = ref(null)
const latestBehaviors = ref([])
const scorePreviewRows = ref([])
const scorePreviewTotal = ref(0)
const scorePreviewPage = ref(1)
const scorePreviewPageSize = ref(50)
const selectedScoreRows = ref([])
const scoreTableRef = ref(null)
const confirmedMappingRows = ref([])
const mappingSchemaHint = ref('')
const currentPublicTask = ref(null)
const currentAnalyzeTask = ref(null)
const lastStartedTaskId = ref('')
const scoreEditDialogVisible = ref(false)
const editingScoreRow = ref(null)

const importForm = reactive({
  batchSize: 5000,
  maxRows: 100000
})
const importUploadRef = ref(null)
const selectedImportFile = ref(null)
const selectedMappingFiles = reactive({ source: null, product: null })

const handleMappingFileChange = (key, file) => {
  selectedMappingFiles[key] = file?.raw || null
}
const handleMappingFileRemove = (key) => {
  selectedMappingFiles[key] = null
}

const analyzeForm = reactive({
  clusterK: 5
})

const DEFAULT_CDP_URL = 'http://host.docker.internal:9223'

const mappingForm = reactive({
  sourceDataPath: '',
  productPath: '',
  generatedProductPath: 'crawler/output/internal_products.auto.csv',
  fixtureDir: '',
  topK: 5,
  maxProducts: 50,
  candidateOutputPath: 'crawler/output/recalled_candidates.browser.csv',
  scoreOutputPath: 'crawler/output/recalled_candidate_scores.csv',
  cdpUrl: DEFAULT_CDP_URL
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
let analyzeTaskTimer = null
const PUBLIC_TASK_STORAGE_KEY = 'data-manage:last-public-task-id'

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
      '',
      'crawler/output',
      ''
    )
    persistPublicTaskId(res.data.taskId || '')
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

const handleAttachedSearchCrawl = async () => {
  crawling.value = true
  addLog('开始附着当前已打开的京东搜索页采集公网指标...', 'warning')

  try {
    const res = await crawlAttachedSearchData(
      mappingForm.candidateOutputPath,
      'crawler/output/jd_search_browser_metrics_attached.csv',
      mappingForm.cdpUrl || DEFAULT_CDP_URL
    )
    persistPublicTaskId(res.data.taskId || '')
    lastStartedTaskId.value = res.data.taskId || ''
    currentPublicTask.value = {
      taskId: res.data.taskId,
      taskType: 'crawl_attached_search',
      running: true,
      progress: 1,
      status: 'running',
      message: '附着搜索页公网指标采集任务已启动'
    }
    addLog(`附着搜索页公网指标采集任务已启动，任务ID: ${res.data.taskId}`, 'primary')
    pollPublicTask(res.data.taskId, {
      onSuccess: (task) => {
        crawlResult.value = task.result || {}
        const imported = task.result?.importedRows ?? '部分'
        addLog(`附着搜索页公网指标采集完成，输出 ${imported} 条商品指标，并已尝试回写数据库。`, 'success')
        ElMessage.success(`成功附着搜索页采集 ${imported} 条公网商品指标`)
      },
      onFailed: (task) => {
        addLog('附着搜索页采集失败: ' + (task.message || '任务执行失败'), 'danger')
      },
      onFinally: () => {
        crawling.value = false
      }
    })
  } catch (error) {
    addLog('附着搜索页采集失败: ' + (error.response?.data?.message || error.message), 'danger')
    crawling.value = false
  }
}

const handleRecallCandidates = async () => {
  recallingCandidates.value = true
  addLog('开始召回公网映射候选商品...', 'warning')

  try {
    // 先上传选中的本地文件，拿到服务器路径
    let sourceDataPath = mappingForm.sourceDataPath
    let productPath = mappingForm.productPath

    if (selectedMappingFiles.source) {
      addLog(`上传原始行为文件: ${selectedMappingFiles.source.name}...`, 'primary')
      const res = await uploadMappingFile(selectedMappingFiles.source)
      sourceDataPath = res.data.serverPath
      mappingForm.sourceDataPath = sourceDataPath
      addLog(`原始行为文件上传完成`, 'success')
    }
    if (selectedMappingFiles.product) {
      addLog(`上传商品快照文件: ${selectedMappingFiles.product.name}...`, 'primary')
      const res = await uploadMappingFile(selectedMappingFiles.product)
      productPath = res.data.serverPath
      mappingForm.productPath = productPath
      addLog(`商品快照文件上传完成`, 'success')
    }

    const res = await recallPublicMappingCandidates(
      productPath,
      mappingForm.candidateOutputPath,
      mappingForm.fixtureDir,
      sourceDataPath,
      mappingForm.generatedProductPath,
      mappingForm.topK,
      mappingForm.maxProducts,
      mappingForm.cdpUrl
    )
    persistPublicTaskId(res.data.taskId || '')
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
        scorePreviewTotal.value = 0
        scorePreviewPage.value = 1
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
    persistPublicTaskId(res.data.taskId || '')
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
        scorePreviewPage.value = 1
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

const clearAnalyzeTaskTimer = () => {
  if (analyzeTaskTimer) {
    clearInterval(analyzeTaskTimer)
    analyzeTaskTimer = null
  }
}

const persistPublicTaskId = (taskId) => {
  if (!taskId) {
    localStorage.removeItem(PUBLIC_TASK_STORAGE_KEY)
    return
  }
  localStorage.setItem(PUBLIC_TASK_STORAGE_KEY, taskId)
}

const handleCancelTask = async () => {
  const taskId = currentPublicTask.value?.taskId || lastStartedTaskId.value
  if (!taskId) return

  cancellingTask.value = true
  try {
    await cancelPublicTask(taskId)
    addLog('已发送取消信号，任务将在当前搜索完成后停止', 'warning')
    ElMessage.warning('已发送取消信号，任务将在当前搜索完成后停止')
  } catch (error) {
    addLog('取消失败: ' + (error.response?.data?.message || error.message), 'danger')
    ElMessage.error('取消失败')
  } finally {
    cancellingTask.value = false
  }
}

const pollTaskProgress = (taskId, options = {}) => {
  const {
    timerType = 'public',
    setTask = () => {},
    persistTaskId = false,
    onProgress,
    onSuccess,
    onFailed,
    onFinally
  } = options
  const clearTimer = timerType === 'analyze' ? clearAnalyzeTaskTimer : clearPublicTaskTimer

  clearTimer()
  const tick = async () => {
    try {
      const res = await getPublicTaskProgress(taskId)
      const task = res.data
      setTask(task)
      if (persistTaskId) {
        persistPublicTaskId(task?.taskId || '')
      }
      if (onProgress) onProgress(task)
      if (task?.running) {
        return
      }
      clearTimer()
      if (task?.status === 'success') {
        if (onSuccess) await onSuccess(task)
      } else {
        if (onFailed) onFailed(task)
      }
      if (onFinally) onFinally(task)
    } catch (error) {
      clearTimer()
      if (onFailed) onFailed({ message: error.response?.data?.message || error.message })
      if (onFinally) onFinally()
    }
  }
  const timer = setInterval(tick, 2000)
  if (timerType === 'analyze') {
    analyzeTaskTimer = timer
  } else {
    publicTaskTimer = timer
  }
  tick()
}

const pollPublicTask = (taskId, handlers = {}) => pollTaskProgress(taskId, {
  ...handlers,
  timerType: 'public',
  setTask: (task) => {
    currentPublicTask.value = task
  },
  persistTaskId: true
})

const pollAnalyzeTask = (taskId, handlers = {}) => pollTaskProgress(taskId, {
  ...handlers,
  timerType: 'analyze',
  setTask: (task) => {
    currentAnalyzeTask.value = task
  },
  onProgress: (task) => {
    analyzing.value = !!task?.running
    if (handlers.onProgress) handlers.onProgress(task)
  }
})

const loadScorePreview = async () => {
  loadingScorePreview.value = true
  try {
    const res = await previewPublicMappingScores(
      mappingForm.scoreOutputPath,
      scorePreviewPage.value,
      scorePreviewPageSize.value
    )
    const preview = res.data || {}
    scorePreviewRows.value = Array.isArray(preview.rows) ? preview.rows : []
    scorePreviewTotal.value = Number(preview.total || 0)
    selectedScoreRows.value = []
    await nextTick()
    selectSuggestedRows()
  } catch (error) {
    scorePreviewRows.value = []
    scorePreviewTotal.value = 0
    addLog('评分结果预览加载失败: ' + (error.response?.data?.message || error.message), 'danger')
  } finally {
    loadingScorePreview.value = false
  }
}

const handleScorePreviewPageChange = (page) => {
  scorePreviewPage.value = page
  loadScorePreview()
}

const handleScorePreviewSizeChange = (pageSize) => {
  scorePreviewPageSize.value = pageSize
  scorePreviewPage.value = 1
  loadScorePreview()
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

const handleImportFileChange = (file) => {
  selectedImportFile.value = file?.raw || null
}

const handleImportFileRemove = () => {
  selectedImportFile.value = null
}

const beginImportProgressPolling = () => {
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
}

const handleImport = async () => {
  const hasUploadFile = !!selectedImportFile.value
  if (!hasUploadFile) {
    ElMessage.warning('请先选择要上传的 CSV 文件')
    return
  }

  const maxUploadSize = 200 * 1024 * 1024
  if (selectedImportFile.value.size > maxUploadSize) {
    const message = 'CSV 文件超过 200MB，请先拆分或压缩后再导入'
    ElMessage.error(message)
    addLog('导入失败: ' + message, 'danger')
    return
  }

  if (progressTimer) clearInterval(progressTimer)
  importing.value = true
  importProgress.value = 0
  importStatus.value = {
    ...createEmptyImportStatus(),
    importing: true,
    filePath: selectedImportFile.value?.name || '',
    message: '导入任务已启动'
  }
  addLog(`开始上传并导入文件: ${selectedImportFile.value?.name || 'CSV'}（自动预处理已启用）`, 'primary')

  try {
    await uploadImportFile(selectedImportFile.value, importForm.batchSize, importForm.maxRows)
    ElMessage.success('导入任务已启动')
    beginImportProgressPolling()
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
    const res = await startAnalyzeTask(analyzeForm.clusterK)
    currentAnalyzeTask.value = {
      taskId: res.data.taskId,
      taskType: 'analyze',
      running: true,
      progress: 1,
      status: 'running',
      message: '分析任务已启动'
    }
    addLog(`分析任务已启动，任务ID: ${res.data.taskId}`, 'primary')
    pollAnalyzeTask(res.data.taskId, {
      onSuccess: async (task) => {
        addLog('数据分析完成', 'success')
        ElMessage.success('数据分析完成')
        await loadOverview()
      },
      onFailed: (task) => {
        addLog('分析失败: ' + (task.message || '任务执行失败'), 'danger')
      },
      onFinally: () => {
        analyzing.value = false
      }
    })
    return
  } catch (error) {
    addLog('分析失败: ' + (error.response?.data?.message || error.message), 'danger')
  } finally {
    if (!(currentAnalyzeTask.value?.taskType === 'analyze' && currentAnalyzeTask.value?.running)) {
      analyzing.value = false
    }
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
  return `商品快照: ${snapshotPath} | 候选文件: ${candidatePath} | 下一步请执行"计算映射分数"`
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
  const persistedTaskId = localStorage.getItem(PUBLIC_TASK_STORAGE_KEY) || ''
  if (persistedTaskId) {
    lastStartedTaskId.value = persistedTaskId
    pollPublicTask(persistedTaskId, {
      onFinally: (task) => {
        if (task?.taskType === 'analyze') {
          analyzing.value = false
        }
      }
    })
  }
})

onUnmounted(() => {
  if (progressTimer) clearInterval(progressTimer)
  clearPublicTaskTimer()
  clearAnalyzeTaskTimer()
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
  
  .analyze-task-tip {
    margin-bottom: 16px;

    .analyze-task-text {
      margin-top: 8px;
      font-size: 13px;
      color: var(--text-secondary);
      line-height: 1.6;
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
      align-items: center;
    }

    .preview-page-meta {
      font-size: 12px;
      color: var(--text-secondary);
    }

    .pagination-wrap {
      margin-top: 12px;
      display: flex;
      justify-content: flex-end;
    }
  }
  
  .mt-2 { margin-top: 8px; }
}
</style>
