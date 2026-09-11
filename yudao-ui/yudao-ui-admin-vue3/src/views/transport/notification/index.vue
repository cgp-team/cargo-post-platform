<template>
  <ContentWrap title="用户通知管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="用户编号">
          <el-input v-model="queryParams.userId" placeholder="会员编号" clearable @keyup.enter="getList" style="width:140px" />
        </el-form-item>
        <el-form-item label="订单编号">
          <el-input v-model="queryParams.orderId" placeholder="运输订单编号" clearable @keyup.enter="getList" style="width:160px" />
        </el-form-item>
        <el-form-item label="阅读状态">
          <el-select v-model="queryParams.readStatus" placeholder="请选择" clearable style="width:130px">
            <el-option label="未读" :value="0" />
            <el-option label="已读" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
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
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>

    <el-dialog v-model="sendVisible" title="发送用户通知" width="520px">
      <el-form label-width="90px">
        <el-form-item label="用户编号" required>
          <el-input-number v-model="sendForm.userId" :min="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="关联订单">
          <el-input-number v-model="sendForm.orderId" :min="0" controls-position="right" placeholder="可空" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="sendForm.title" placeholder="请输入通知标题" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="sendForm.content" type="textarea" :rows="3" placeholder="请输入通知内容" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSend">发送</el-button>
      </template>
    </el-dialog>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as NotificationApi from '@/api/transport/notification'

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

const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, userId: undefined, orderId: undefined, readStatus: undefined })
  getList()
}

const openSend = () => {
  Object.assign(sendForm, { userId: undefined, orderId: undefined, title: '', content: '' })
  sendVisible.value = true
}

const submitSend = async () => {
  if (!sendForm.userId || !sendForm.title) {
    message.warning('请填写用户编号与标题')
    return
  }
  try {
    await NotificationApi.sendNotification(sendForm)
    message.success('发送成功')
    sendVisible.value = false
    getList()
  } catch (e) {
    // 错误已由请求层提示
  }
}

onMounted(getList)
</script>
