<template>
  <ContentWrap title="用户钱包">
    <el-form :inline="true" class="mb-8px">
      <el-form-item label="用户 ID">
        <el-input-number v-model="userId" :min="1" :controls="false" placeholder="输入用户 ID" style="width: 180px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="accLoading" :disabled="!userId" @click="query">
          <Icon icon="ep:search" />查询
        </el-button>
      </el-form-item>
    </el-form>

    <!-- 为什么不给"调整余额"按钮：这件事写在页面上，而不是藏起来 -->
    <el-alert
      type="info"
      :closable="false"
      class="mb-12px"
      title="这一页只提供查询，不提供改账"
      description="后端确实有 POST /transport/wallet/adjust-balance，但它没有幂等键（重复点击就重复加/扣钱），且金额为 0 时两条分支都不走、照样返回「调整成功」。这两条补齐之前，后台不暴露该操作。"
    />

    <el-card shadow="never" class="mb-12px">
      <template #header>
        <span>{{ queried ? `账户（用户 ${userId}）` : '账户（还没查询）' }}</span>
      </template>
      <div v-if="accError" class="wallet-error">{{ accError }}</div>
      <el-descriptions v-else :column="3" border>
        <el-descriptions-item label="可用余额">{{ money(acc?.balance) }}</el-descriptions-item>
        <el-descriptions-item label="冻结金额">{{ money(acc?.frozenAmount) }}</el-descriptions-item>
        <el-descriptions-item label="累计充值">{{ money(acc?.totalRecharge) }}</el-descriptions-item>
        <el-descriptions-item label="累计支出">{{ money(acc?.totalSpent) }}</el-descriptions-item>
        <el-descriptions-item label="账户状态">{{ statusText(acc?.status) }}</el-descriptions-item>
        <el-descriptions-item label="记账口径">
          流水状态码含义来自建表注释 V028:31，后端没有对应枚举类
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="!queried && !accError" class="wallet-hint">
        输入用户 ID 后点查询；这一页不会先显示 ¥0.00 再让你以为是空的。
      </div>
    </el-card>

    <el-table v-loading="txLoading" :data="txList" stripe border>
      <el-table-column label="流水号" prop="transactionNo" align="center" min-width="180" />
      <el-table-column label="类型" align="center" width="120">
        <template #default="scope">{{ typeText(scope.row.type) }}</template>
      </el-table-column>
      <el-table-column label="金额" align="center" width="120">
        <template #default="scope">{{ money(scope.row.amount) }}</template>
      </el-table-column>
      <el-table-column label="变动后余额" align="center" width="130">
        <template #default="scope">{{ money(scope.row.balanceAfter) }}</template>
      </el-table-column>
      <el-table-column label="摘要" prop="title" align="center" min-width="150" />
      <el-table-column label="渠道（后端原词）" align="center" width="150">
        <template #default="scope">{{ scope.row.payMethod || '后端没给' }}</template>
      </el-table-column>
      <el-table-column label="状态" align="center" width="120">
        <template #default="scope">{{ statusText(scope.row.status) }}</template>
      </el-table-column>
      <el-table-column label="时间" align="center" width="180">
        <template #default="scope">{{ timeText(scope.row.createTime) }}</template>
      </el-table-column>
      <template #empty>
        <el-empty :image-size="60" :description="txEmptyText" />
      </template>
    </el-table>
    <Pagination
      :total="txTotal"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="loadTransactions"
    />
  </ContentWrap>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import * as WalletApi from '@/api/transport/wallet'

defineOptions({ name: 'TransportWallet' })

const userId = ref<number>()
const queried = ref(false)
const accLoading = ref(false)
const accError = ref('')
const acc = ref<WalletApi.WalletAccountVO>()

const txLoading = ref(false)
const txList = ref<WalletApi.WalletTransactionVO[]>([])
const txTotal = ref(0)
const txError = ref('')
const txQueried = ref(false)
const queryParams = reactive({ pageNo: 1, pageSize: 10 })

/** 缺席就是缺席：不许显示成 ¥0.00（"真零"与"没给"是两格） */
const money = (v?: number) =>
  v === undefined || v === null ? '后端没给' : '¥' + Number(v).toFixed(2)

/** 码→文案照抄建表注释 V028:31；未知码原样带出不猜，null 不塌成"处理中" */
const statusText = (v?: number) => {
  if (v === undefined || v === null) return '后端没给'
  if (v === 0) return '处理中'
  if (v === 1) return '成功'
  if (v === 2) return '失败'
  return `未识别状态（${v}）`
}

/** 流水类型值域来自 V028:25（recharge/consume/refund/withdraw）；空 = 没给，不就近并进"消费" */
const typeText = (v?: string) => {
  if (!v) return '后端没给'
  const map: Record<string, string> = { recharge: '充值', consume: '消费', refund: '退款', withdraw: '提现' }
  return map[v] || `未识别类型（${v}）`
}

/** createTime 是 Epoch 毫秒（YudaoJackson 把 LocalDateTime 注册成时间戳），不是 ISO 串 */
const timeText = (v?: number) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '后端没给')

const txEmptyText = computed(() => {
  if (txError.value) return txError.value
  if (!txQueried.value) return '输入用户 ID 查询后，这里列该用户的流水'
  return '这个用户没有流水记录'
})

const query = async () => {
  if (!userId.value) return
  queried.value = true
  accError.value = ''
  accLoading.value = true
  try {
    acc.value = await WalletApi.getWalletAccount(userId.value)
  } catch (e: any) {
    acc.value = undefined
    accError.value = `账户没取到：${e?.message || e}（不是"余额为 0"）`
  } finally {
    accLoading.value = false
  }
  queryParams.pageNo = 1
  await loadTransactions()
}

const loadTransactions = async () => {
  txLoading.value = true
  txError.value = ''
  try {
    const res: any = await WalletApi.getWalletTransactionPage({
      userId: userId.value,
      pageNo: queryParams.pageNo,
      pageSize: queryParams.pageSize
    })
    txList.value = res?.list ?? []
    txTotal.value = res?.total ?? 0
    txQueried.value = true
  } catch (e: any) {
    txList.value = []
    txTotal.value = 0
    txError.value = `流水没取到：${e?.message || e}`
  } finally {
    txLoading.value = false
  }
}
</script>

<style scoped>
.wallet-error {
  color: var(--el-color-danger);
  font-size: 13px;
}
.wallet-hint {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
