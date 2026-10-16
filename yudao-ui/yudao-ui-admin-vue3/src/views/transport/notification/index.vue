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
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="编号" prop="id" align="center" width="80" />
        <el-table-column label="用户编号" prop="userId" align="center" width="100" />
        <el-table-column label="事件类型" prop="eventType" align="center" width="170" />
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
const total = ref(0)
const list = ref<NotificationApi.NotificationVO[]>([])
const queryParams = reactive<{ pageNo: number; pageSize: number; userId?: number; orderId?: number; readStatus?: number }>({
  pageNo: 1,
  pageSize: 10,
  userId: undefined,
  orderId: undefined,
  readStatus: undefined
})

const sendVisible = ref(false)
const sendForm = reactive<NotificationApi.NotificationVO>({ userId: undefined, orderId: undefined, title: '', content: '' })

const getList = async () => {
  loading.value = true
  try {
    const res = await NotificationApi.getNotificationPage(queryParams)
    list.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
const handleQuery = () => { queryParams.pageNo = 1; getList() }  // 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"

const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, userId: undefined, orderId: undefined, readStatus: undefined })
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

onMounted(getList)
</script>
