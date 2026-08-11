<template>
  <ContentWrap title="商品管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="商品名称">
          <el-input v-model="queryParams.name" placeholder="请输入商品名称" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="上架" :value="0" />
            <el-option label="下架" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:product:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增商品
      </el-button>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="商品名称" prop="name" align="center" min-width="140" />
        <el-table-column label="商品图" align="center" width="80">
          <template #default="scope">
            <span style="font-size: 28px">{{ scope.row.image }}</span>
          </template>
        </el-table-column>
        <el-table-column label="产地村庄" prop="fromVillage" align="center" width="110" />
        <el-table-column label="价格" align="center" width="100">
          <template #default="scope">¥{{ scope.row.price }}</template>
        </el-table-column>
        <el-table-column label="单位" prop="unit" align="center" width="70" />
        <el-table-column label="角标" prop="badge" align="center" width="120" />
        <el-table-column label="库存" prop="stock" align="center" width="80" />
        <el-table-column label="状态" align="center" width="80">
          <template #default="scope">
            <el-tag :type="scope.row.status === 0 ? 'success' : 'danger'">{{ scope.row.status === 0 ? '上架' : '下架' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" prop="sort" align="center" width="70" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:product:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:product:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <ProductForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as ProductApi from '@/api/transport/product'
import ProductForm from './ProductForm.vue'

defineOptions({ name: 'TransportProduct' })

const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref([])

type ProductQueryParams = {
  pageNo: number
  pageSize: number
  name?: string
  status?: number
}
const queryParams = reactive<ProductQueryParams>({ pageNo: 1, pageSize: 10, name: '', status: undefined })
const formRef = ref()

const getList = async () => {
  loading.value = true
  try {
    const res = await ProductApi.getProductPage(queryParams)
    list.value = res.list
    total.value = res.total
  } finally { loading.value = false }
}
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, name: '', status: undefined }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该商品？'); await ProductApi.deleteProduct(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}
onMounted(getList)
</script>
