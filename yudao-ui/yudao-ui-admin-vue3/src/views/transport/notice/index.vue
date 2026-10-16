<template>
  <ContentWrap title="公告管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="公告标题">
          <el-input v-model="queryParams.title" placeholder="请输入公告标题" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width:160px">
            <el-option label="上架" :value="1" />
            <el-option label="下架" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:notice:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增公告
      </el-button>
      <el-alert
          v-if="listError"
          type="error"
          :closable="false"
          show-icon
          :title="listError"
          description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
      />
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="公告标题" prop="title" align="center" show-overflow-tooltip />
        <el-table-column label="公告内容" prop="content" align="center" show-overflow-tooltip />
        <el-table-column label="状态" prop="status" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.status === 1 ? 'success' : 'info'">
              {{ scope.row.status === 1 ? '上架' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="sort" align="center" width="80" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:notice:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:notice:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无公告，点击下方按钮发布第一条公告">
            <el-button type="primary" @click="openForm('create')">新增公告</el-button>
          </el-empty>
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <NoticeForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as NoticeApi from '@/api/transport/notice'
import NoticeForm from './NoticeForm.vue'

defineOptions({ name: 'TransportNotice' })

const message = useMessage()
const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<NoticeApi.NoticeVO[]>([])
type NoticeQueryParams = {
  pageNo: number
  pageSize: number
  title?: string
  status?: number
}

const queryParams = reactive<NoticeQueryParams>({
  pageNo: 1,
  pageSize: 10,
  title: '',
  status: undefined,
})
const formRef = ref()

const getList = async () => {
  loading.value = true
  try {
    const res = await NoticeApi.getNoticePage(queryParams)
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

const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, title: '', status: undefined }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该公告？'); await NoticeApi.deleteNotice(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}

onMounted(getList)
</script>
