<template>
  <ContentWrap title="意见反馈处理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="姓名">
          <el-input v-model="queryParams.name" placeholder="请输入姓名" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="queryParams.mobile" placeholder="请输入手机号" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width:160px">
            <el-option label="待处理" :value="0" />
            <el-option label="已回复" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" v-hasPermi="['transport:feedback:query']" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-alert
          v-if="listError"
          type="error"
          :closable="false"
          show-icon
          :title="listError"
          description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
      />
      <el-table v-loading="loading" :data="list" stripe border>
        <el-table-column label="姓名" prop="name" align="center" width="100" />
        <el-table-column label="手机号" prop="mobile" align="center" width="130" />
        <el-table-column label="反馈内容" prop="content" align="center" show-overflow-tooltip />
        <el-table-column label="状态" prop="status" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.status === 1 ? 'success' : 'warning'">
              {{ scope.row.status === 1 ? '已回复' : '待处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回复内容" prop="reply" align="center" show-overflow-tooltip />
        <el-table-column label="反馈时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="100">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:feedback:reply']" @click="openReply(scope.row.id)">
              {{ scope.row.status === 1 ? '修改回复' : '回复' }}
            </el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无反馈记录" />
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <FeedbackReplyForm ref="replyFormRef" @success="getList" />
</template>

<script setup lang="ts">
import * as FeedbackApi from '@/api/transport/operation/feedback'
import FeedbackReplyForm from './FeedbackReplyForm.vue'

defineOptions({ name: 'TransportOperationFeedback' })

const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<FeedbackApi.FeedbackVO[]>([])
type FeedbackQueryParams = {
  pageNo: number
  pageSize: number
  name?: string
  mobile?: string
  status?: number
}

const queryParams = reactive<FeedbackQueryParams>({
  pageNo: 1,
  pageSize: 10,
  name: '',
  mobile: '',
  status: undefined,
})
const replyFormRef = ref()

const getList = async () => {
  loading.value = true
  try {
    const res = await FeedbackApi.getFeedbackPage(queryParams)
    list.value = res.list
    total.value = res.total
  } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    // （队列 #68 / 台账 D-134；判据 scripts/check_admin_catch_leaves_data.py）
    list.value = []
    total.value = 0
    listError.value = '列表没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有数据」）')
  } finally { loading.value = false }
}
const handleQuery = () => { queryParams.pageNo = 1; getList() }  // 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"

const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, name: '', mobile: '', status: undefined }); getList() }
const openReply = (id: number) => replyFormRef.value?.open(id)

onMounted(getList)
</script>
