<template>
  <AdminLayout active="iktv">
    <section class="iktv-fusion" aria-label="IKTV 引用融合管理">
      <header class="page-head">
        <div>
          <h1>IKTV 引用融合</h1>
          <p>融合会改写歌曲编号与分类标签，直接影响点歌端分类入口。</p>
        </div>
        <button type="button" class="ghost" :disabled="loading" @click="loadStatus">
          {{ loading ? '读取中…' : '刷新状态' }}
        </button>
      </header>

      <div v-if="statusError" class="card card-error" role="alert">
        <span>融合状态读取失败：{{ statusError }}</span>
        <button type="button" class="ghost" @click="loadStatus">重试</button>
      </div>

      <template v-else>
        <div class="card-row">
          <article class="card">
            <h2>当前快照</h2>
            <p v-if="status?.activeSnapshotVersion" class="big">{{ status.activeSnapshotVersion }}</p>
            <p v-else class="muted">尚未发布任何快照</p>
          </article>

          <article class="card">
            <h2>暂停状态</h2>
            <template v-if="status?.pausedSnapshotVersion">
              <p class="big">已暂停 {{ status.pausedSnapshotVersion }}</p>
              <p class="muted">批次 #{{ status.pausedByBatchId }} · {{ status.pauseReason || '未提供原因' }}</p>
              <p class="muted">{{ status.pausedAt || '—' }}</p>
            </template>
            <p v-else class="muted">未暂停</p>
          </article>
        </div>

        <div class="risk-note">
          融合会改写歌曲编号与分类标签，影响点歌端「高清专区 / 抖音神曲 / 综艺点歌 / 主题点歌」等分类入口；已被元数据锁保护的字段不会被覆盖。
        </div>

        <article class="card">
          <h2>操作</h2>
          <div class="op-row">
            <button type="button" class="primary" :disabled="!canResume || busy" @click="resume">恢复融合</button>
            <label class="batch-input">
              批次号
              <input v-model="batchIdInput" inputmode="numeric" placeholder="例如 12" />
            </label>
            <button type="button" class="ghost" :disabled="!batchId || busy" @click="loadBatch">查询批次</button>
            <button type="button" class="danger" :disabled="!batchId || busy" @click="rollback">回滚</button>
          </div>
        </article>

        <article v-if="batch" class="card">
          <h2>批次 #{{ batch.id }}</h2>
          <dl class="batch-grid">
            <div><dt>状态</dt><dd>{{ batch.state || '—' }}</dd></div>
            <div><dt>发布状态</dt><dd>{{ batch.publicationState || '—' }}</dd></div>
            <div><dt>总数</dt><dd>{{ batch.totalCount ?? 0 }}</dd></div>
            <div><dt>已处理</dt><dd>{{ batch.processedCount ?? 0 }}</dd></div>
            <div><dt>已匹配</dt><dd>{{ batch.matchedCount ?? 0 }}</dd></div>
            <div><dt>未匹配</dt><dd>{{ batch.unmatchedCount ?? 0 }}</dd></div>
            <div><dt>冲突</dt><dd>{{ batch.conflictCount ?? 0 }}</dd></div>
            <div><dt>受保护</dt><dd>{{ batch.protectedCount ?? 0 }}</dd></div>
            <div><dt>失败</dt><dd>{{ batch.failedCount ?? 0 }}</dd></div>
            <div><dt>回滚冲突</dt><dd>{{ batch.rollbackConflictCount ?? 0 }}</dd></div>
            <div><dt>最近错误</dt><dd>{{ batch.lastErrorCode || '—' }}</dd></div>
          </dl>
        </article>

        <article v-for="card in resultCards" :key="card.key" class="card">
          <h2>{{ card.title }}</h2>
          <dl class="batch-grid">
            <div><dt>批次</dt><dd>#{{ card.data.batchId }}</dd></div>
            <div><dt>状态</dt><dd>{{ card.data.state || '—' }}</dd></div>
            <div><dt>编号变更</dt><dd>{{ card.data.catalogNumbersChanged ?? 0 }}</dd></div>
            <div><dt>分类变更</dt><dd>{{ card.data.categoriesChanged ?? 0 }}</dd></div>
            <div><dt>冲突条目</dt><dd>{{ card.data.conflictRows ?? 0 }}</dd></div>
          </dl>
          <p v-if="(card.data.conflictRows || 0) > 0" class="conflict-alert" role="alert">
            有 {{ card.data.conflictRows }} 条冲突未处理，已保持原值。
          </p>
        </article>
      </template>
    </section>
  </AdminLayout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../../api/client'
import { alertDialog, confirmDialog } from '../../composables/useDialog'
import AdminLayout from './AdminLayout.vue'

const status = ref(null)
const statusError = ref('')
const batch = ref(null)
const resumeResult = ref(null)
const rollbackResult = ref(null)
const batchIdInput = ref('')
const loading = ref(false)
const busy = ref(false)

/**
 * 结果卡片：恢复与回滚各自一张，不能共用同一个 ref。
 *
 * 共用会让「恢复融合」的结果顶着「回滚结果」的标题显示，管理员会以为融合被回退了。
 */
const resultCards = computed(() => {
  const cards = []
  if (resumeResult.value) {
    cards.push({ key: 'resume', title: '恢复结果', data: resumeResult.value })
  }
  if (rollbackResult.value) {
    cards.push({ key: 'rollback', title: '回滚结果', data: rollbackResult.value })
  }
  return cards
})

const batchId = computed(() => {
  const parsed = Number(batchIdInput.value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : 0
})

const canResume = computed(() => Boolean(status.value?.pausedSnapshotVersion))

async function loadStatus() {
  loading.value = true
  statusError.value = ''
  try {
    status.value = await api.adminIktvFusionStatus()
  } catch (error) {
    statusError.value = error?.message || '网络错误'
  } finally {
    loading.value = false
  }
}

async function loadBatch() {
  if (!batchId.value) return
  busy.value = true
  try {
    batch.value = await api.adminIktvFusionBatch(batchId.value)
  } catch (error) {
    await alertDialog(error?.message || '批次查询失败')
  } finally {
    busy.value = false
  }
}

async function resume() {
  if (!canResume.value) return
  busy.value = true
  try {
    resumeResult.value = await api.adminIktvFusionResume()
    await loadStatus()
  } catch (error) {
    await alertDialog(error?.message || '恢复融合失败')
  } finally {
    busy.value = false
  }
}

async function rollback() {
  const id = batchId.value
  if (!id) return
  const confirmed = await confirmDialog(
    `即将回滚批次 #${id} 的融合结果。\n\n`
    + '该操作会回退歌曲编号与分类标签，点歌端「高清专区 / 抖音神曲 / 综艺点歌 / 主题点歌」等分类入口的数据会随之变化。\n\n'
    + '已被元数据锁（metadata_locks）锁定的字段不会被覆盖。\n\n'
    + '回滚可能产生冲突，冲突条目将保持原值并在结果中报告。\n\n确定继续？'
  )
  if (!confirmed) return
  busy.value = true
  try {
    rollbackResult.value = await api.adminIktvFusionRollback(id)
    batch.value = await api.adminIktvFusionBatch(id)
    await loadStatus()
  } catch (error) {
    await alertDialog(error?.message || '回滚失败')
  } finally {
    busy.value = false
  }
}

onMounted(loadStatus)
</script>

<style scoped>
.iktv-fusion {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.page-head h1 {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 600;
  color: #172033;
}
.page-head p {
  margin: 0;
  font-size: 13px;
  color: #64748b;
}
.card-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.card {
  flex: 1;
  min-width: 280px;
  padding: 18px 20px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
}
.card h2 {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #475569;
}
.card .big {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
  color: #172033;
  overflow-wrap: anywhere;
  word-break: break-all;
}
.card .muted {
  margin: 6px 0 0;
  font-size: 13px;
  color: #64748b;
}
.card-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-color: #f5c2c2;
  background: #fef2f2;
  color: #b42318;
  font-size: 13px;
}
.risk-note {
  padding: 12px 16px;
  border-radius: 8px;
  border: 1px solid #fde68a;
  background: #fffbeb;
  color: #92400e;
  font-size: 13px;
  line-height: 1.6;
}
.op-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.batch-input {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #475569;
}
.batch-input input {
  width: 110px;
  padding: 6px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 13px;
}
.primary,
.ghost,
.danger {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.primary {
  border: 1px solid #1d4ed8;
  background: #2563eb;
  color: #fff;
}
.ghost {
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #475569;
}
.danger {
  border: 1px solid #f09595;
  background: #fef2f2;
  color: #b42318;
}
.primary:disabled,
.ghost:disabled,
.danger:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.batch-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: 12px;
  margin: 0;
}
.batch-grid div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.batch-grid dt {
  font-size: 12px;
  color: #94a3b8;
}
.batch-grid dd {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #172033;
}
.conflict-alert {
  margin: 12px 0 0;
  padding: 10px 14px;
  border-radius: 6px;
  border: 1px solid #f5c2c2;
  background: #fef2f2;
  color: #b42318;
  font-size: 13px;
}
</style>
