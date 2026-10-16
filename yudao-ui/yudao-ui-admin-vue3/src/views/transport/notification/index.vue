<template>
  <ContentWrap title="用户通知管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="用户编号">
          <el-input v-model="queryParams.userId" placeholder="会员编号" clearable @keyup.enter="handleQuery" style="width:140px" />
        </el-form-item>
        <el-form-item label="订单编号">
          <el-input v-model="queryParams.orderId" placeholder="运输订单编号" clearable @keyup.enter="handleQuery" style="width:160px" />
        </el-form-item>
        <el-form-item label="接收方">
          <el-select v-model="queryParams.recipientType" placeholder="请选择" clearable style="width:150px">
            <!-- 三类接收方共用同一张表与同一套编号（#92）：不选就是混着看，选了才问得出"管理员还有几条没处理" -->
            <el-option label="用户" value="USER" />
            <el-option label="司机" value="DRIVER" />
            <el-option label="管理员告警" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="阅读状态">
          <el-select v-model="queryParams.readStatus" placeholder="请选择" clearable style="width:130px">
            <el-option label="未读" :value="0" />
            <el-option label="已读" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>

    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:notification:send']" @click="openSend">
        <Icon icon="ep:plus" />发送通知
      </el-button>
      <!-- #92 管理员待处理那一格：告警一直写得进这张表（sendToAdmin 六处），但后台没有任何口径问得出
           "还有几条没处理"，所以它实际上是通道通了、队列没人看。三种状态分开：正在数 / 没读到 / 数字。 -->
      <el-alert
          v-if="!unreadLoading && unreadError"
          type="warning"
          :closable="false"
          show-icon
          :title="unreadError"
          description="这一格只说明未处理数这次没读到；它为空不代表今天没有告警。"
          style="margin-top:16px"
      />
      <div v-else style="margin-top:16px; display:flex; align-items:center; gap:8px">
        <span v-if="unreadLoading">正在数未处理的告警…</span>
        <template v-else>
          <span>管理员未处理告警：<b>{{ unread.total === undefined ? '—' : unread.total }}</b> 条</span>
          <el-tag v-if="unread.EXCEPTION" type="danger" size="small">异常 {{ unread.EXCEPTION }}</el-tag>
          <el-tag v-if="unread.WARNING" type="warning" size="small">警告 {{ unread.WARNING }}</el-tag>
          <el-tag v-if="unread.ACTION_REQUIRED" type="warning" size="small">待操作 {{ unread.ACTION_REQUIRED }}</el-tag>
        </template>
        <el-button size="small" :loading="unreadLoading" @click="getAdminUnread">重新数一次</el-button>
      </div>
      <el-alert
          v-if="listError"
          type="error"
          :closable="false"
          show-icon
          :title="listError"
          description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
      />
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="编号" prop="id" align="center" width="80" />
        <el-table-column label="用户编号" prop="userId" align="center" width="100" />
        <el-table-column label="事件类型" prop="eventType" align="center" width="170" />
        <el-table-column label="接收方" prop="recipientType" align="center" width="110">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.recipientType === 'ADMIN' ? 'danger' : 'info'">
              {{ recipientText(scope.row.recipientType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="级别" prop="level" align="center" width="100">
          <template #default="scope">{{ levelText(scope.row.level) }}</template>
        </el-table-column>
        <el-table-column label="标题" prop="title" align="center" width="180" show-overflow-tooltip />
        <el-table-column label="内容" prop="content" align="center" show-overflow-tooltip />
        <el-table-column label="订单编号" prop="orderId" align="center" width="100" />
        <el-table-column label="状态" prop="readStatus" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.readStatus === 1 ? 'info' : 'warning'">
              {{ scope.row.readStatus === 1 ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" align="center" width="170" />
        <el-table-column label="操作" align="center" width="140">
          <template #default="scope">
            <!-- 只有管理员那一格能从后台确认；用户/司机的已读归各自的端（P2-L：三类共用编号空间，串了就读不到） -->
            <el-button
                v-if="scope.row.recipientType === 'ADMIN' && scope.row.readStatus === 0"
                type="primary"
                size="small"
                :loading="processedLoading"
                @click="confirmProcessed(scope.row)"
            >标记已处理</el-button>
            <span v-else style="color:#909399">—</span>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无通知记录" />
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>

    <!-- WEB-07: 改回 Dialog 封装 -->
    <Dialog v-model="sendVisible" title="发送用户通知" width="480px">
      <el-form ref="sendFormRef" :model="sendForm" :rules="sendRules" label-width="90px">
        <el-form-item label="用户编号" prop="userId">
          <el-input-number v-model="sendForm.userId" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="关联订单">
          <el-input-number v-model="sendForm.orderId" :min="0" controls-position="right" placeholder="可空" />
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="sendForm.title" placeholder="请输入通知标题" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="sendForm.content" type="textarea" :rows="3" placeholder="请输入通知内容" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sendLoading" @click="submitSend">发送</el-button>
      </template>
    </Dialog>
  </ContentWrap>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import * as NotificationApi from '@/api/transport/notification'
import { Dialog } from '@/components/Dialog'

defineOptions({ name: 'TransportNotification' })

const message = useMessage()
const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<NotificationApi.NotificationVO[]>([])
const queryParams = reactive<{ pageNo: number; pageSize: number; userId?: number; orderId?: number; readStatus?: number; recipientType?: string }>({
  pageNo: 1,
  pageSize: 10,
  userId: undefined,
  orderId: undefined,
  readStatus: undefined,
  recipientType: undefined
})

// #92 管理员待处理那一格：三态分开（正在数 / 没读到 / 数字），空值一律念「—」不念 0
const unread = ref<Record<string, number>>({})
const unreadLoading = ref(false)
const unreadError = ref('')
const processedLoading = ref(false)

const getAdminUnread = async () => {
  unreadLoading.value = true
  try {
    unread.value = await NotificationApi.getAdminUnreadCount()
    unreadError.value = ''
  } catch (e: any) {
    // 清掉旧数字再留字：拿着上一轮的数继续显示，会被读成"现在还这么多"
    unread.value = {}
    unreadError.value = '未处理告警数没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有告警」）')
  } finally {
    unreadLoading.value = false
  }
}

const recipientText = (v?: string) =>
  v === 'ADMIN' ? '管理员' : v === 'DRIVER' ? '司机' : v === 'USER' ? '用户' : '未标注'
const levelText = (v?: string) =>
  v === 'EXCEPTION' ? '异常' : v === 'WARNING' ? '警告' : v === 'ACTION_REQUIRED' ? '待操作'
    : v === 'SUCCESS' ? '完成' : v === 'INFO' ? '信息' : '未标注'

/** 确认一条管理员告警：写口自己说"成功"不算数，回读那行的 readStatus 才算（沿用本页发送那格的形状） */
const confirmProcessed = async (row: NotificationApi.NotificationVO) => {
  if (processedLoading.value) return
  if (!row.id) {
    message.warning('这一行没有编号，确认不了')
    return
  }
  processedLoading.value = true
  let failed = false
  try {
    await NotificationApi.markAdminRead(row.id)
  } catch (e) {
    failed = true
  } finally {
    processedLoading.value = false
  }
  if (failed) return // 请求层已提示；这里不叠第二条，也不谎称已处理
  const back = await NotificationApi.getNotification(row.id).catch(() => undefined)
  if (!back) {
    message.warning('已提交，但这条没读回来（这次没验到，不等于已处理）')
  } else if (back.readStatus !== 1) {
    message.warning(`这条在后端仍是未读（读回 readStatus=${back.readStatus}），本次没确认成功`)
  } else {
    message.success('这条告警已标记为已处理（记时间；表里没有操作人列，所以不记是谁点的）')
  }
  getList()
  getAdminUnread()
}

let unreadTimer: ReturnType<typeof setInterval> | null = null

const sendVisible = ref(false)
const sendForm = reactive<NotificationApi.NotificationVO>({ userId: undefined, orderId: undefined, title: '', content: '' })

const getList = async () => {
  loading.value = true
  try {
    const res = await NotificationApi.getNotificationPage(queryParams)
    list.value = res.list
    total.value = res.total
  } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    // （队列 #68 / 台账 D-134；判据 scripts/check_admin_catch_leaves_data.py）
    list.value = []
    total.value = 0
    listError.value = '列表没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有数据」）')
  } finally {
    loading.value = false
  }
}
const handleQuery = () => { queryParams.pageNo = 1; getList() }  // 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"

const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, userId: undefined, orderId: undefined, readStatus: undefined, recipientType: undefined })
  getList()
}

const openSend = () => {
  Object.assign(sendForm, { userId: undefined, orderId: undefined, title: '', content: '' })
  sendVisible.value = true
}

// WEB-16: 字段级校验规则（替代 toast 手拦，错误以红字呈现）
const sendRules = {
  userId: [{ required: true, message: '请填写用户编号', trigger: 'blur' }],
  title: [{ required: true, message: '请填写标题', trigger: 'blur' }]
}
const sendFormRef = ref()
// 请求在飞时按钮必须不可再点（:loading 会同时禁掉点击）。这一句为什么必须由前端来做，实测过：
// `transport_user_notification` 上 `event_id` **没有唯一索引**（sql/mysql/transport-schema.sql），
// 去重是服务层"先 SELECT 再 INSERT"（UserNotificationServiceImpl.send）⇒ 并发双击会两次 SELECT-miss、插两行。
const sendLoading = ref(false)
const submitSend = async () => {
  try { await sendFormRef.value?.validate() } catch { return } // 校验不过：字段级红字提示
  if (sendLoading.value) return
  // 二次确认（队列 #67）：这条会进用户的消息中心，发出去撤不回
  try {
    await message.confirm(
      `确认给用户 #${sendForm.userId} 发送「${sendForm.title}」？通知会进入对方的消息中心，发出后无法撤回。`,
      '发送用户通知'
    )
  } catch (e) {
    return // 用户取消：什么都没发生
  }
  sendLoading.value = true
  const sentTitle = sendForm.title
  let ackId: number | undefined
  let sendFailed = false
  try {
    ackId = await NotificationApi.sendNotification(sendForm)
  } catch (e) {
    // 写请求本身失败：错误文案由请求层提示，这里只留一条可见分支（不叠第二条 toast，也不去回读）
    sendFailed = true
  } finally {
    sendLoading.value = false
  }
  if (sendFailed) return
  getList()
  // "发送成功"这句原来只由 rc=200 支撑。实测契约：手工发的幂等键是
  // `SYSTEM:USER-<用户>`（带订单才追加 `:ORDER-n`），同一个用户的**第二条不同内容**会命中
  // selectByEvent 被当成重复事件**跳过**，接口照样返回那条旧记录的 id ⇒ 必须由回读证实这次真的落了一条新的。
  // 回读单独一条链：读不回来就念"没验到"，绝不拿一次成功的 rc 冒充"已送达"。
  const back = ackId ? await NotificationApi.getNotification(Number(ackId)).catch(() => undefined) : undefined
  if (!back) {
    message.warning('请求已提交，但这条通知没读回来（这次没验到，不等于已送达）')
  } else if ((back.title || '') !== sentTitle) {
    message.warning(
      `这条没有作为新通知送达：后端按幂等键把它合成了已有的 #${ackId}（内容是「${back.title || '无标题'}」）。` +
        '要给同一个用户发不同内容的通知，请填订单号（幂等键会带 ORDER-n）。'
    )
  } else {
    message.success('发送成功，用户消息中心已读回这条')
    sendVisible.value = false
  }
}

// 告警的新鲜度靠这张页自己再问一次：30 秒一轮，离开页面就停（不清会一直打后端并在后台标签页里累积请求）
onMounted(() => {
  getList()
  getAdminUnread()
  unreadTimer = setInterval(getAdminUnread, 30000)
})
onUnmounted(() => {
  if (unreadTimer) {
    clearInterval(unreadTimer)
    unreadTimer = null
  }
})
</script>
