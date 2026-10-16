<template>
  <ContentWrap title="站点管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="站点编码">
          <el-input v-model="queryParams.stationCode" placeholder="请输入站点编码" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="站点名称">
          <el-input v-model="queryParams.stationName" placeholder="请输入站点名称" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="站点类型">
          <el-select v-model="queryParams.stationType" placeholder="全部" clearable style="width:140px">
            <el-option label="公交站" value="BUS_STOP" />
            <el-option label="货运站" value="CARGO_STATION" />
            <el-option label="混合站" value="MIXED" />
          </el-select>
        </el-form-item>
        <el-form-item label="可调度">
          <el-select v-model="queryParams.dispatchEnabled" placeholder="全部" clearable style="width:120px">
            <el-option label="可调度" :value="true" />
            <el-option label="不可调度" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:station:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增站点
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
        <el-table-column label="站点编码" prop="stationCode" align="center" />
        <el-table-column label="站点名称" prop="stationName" align="center" />
        <el-table-column label="站点级别" prop="stationLevel" align="center" />
        <el-table-column label="类型" prop="stationType" align="center" width="110">
          <template #default="scope">{{ typeText(scope.row.stationType) }}</template>
        </el-table-column>
        <el-table-column label="用户可达" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.userAccess === false ? 'danger' : 'success'">
              {{ scope.row.userAccess === false ? '否' : '是' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="车辆可达" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.vehicleAccess === false ? 'danger' : 'success'">
              {{ scope.row.vehicleAccess === false ? '否' : '是' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="可调度" align="center" width="90">
          <template #default="scope">
            <el-tag :type="scope.row.dispatchEnabled === false ? 'info' : 'warning'">
              {{ scope.row.dispatchEnabled === false ? '否' : '是' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="站点地址" prop="address" align="center" />
        <!-- 揽收点属性（V033 起才有列）。⚠ 空值一律念"没填/未公布"，不显示成空白格——
             空白格在后台语境里会被读成"这一项已核对过、确实没有"。 -->
        <el-table-column label="联系人" align="center" width="150">
          <template #default="scope">
            <span v-if="scope.row.contactName">{{ scope.row.contactName }}</span>
            <span v-else class="text-gray-400">没填</span>
            <span v-if="scope.row.contactMobile" class="text-gray-500"> · {{ scope.row.contactMobile }}</span>
          </template>
        </el-table-column>
        <el-table-column label="营业时间" align="center" width="140">
          <template #default="scope">
            <span v-if="scope.row.businessHours">{{ scope.row.businessHours }}</span>
            <span v-else class="text-gray-400">未公布</span>
          </template>
        </el-table-column>
        <el-table-column label="电商站点" align="center" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.ecommerceFlag" size="small" type="warning">电商</el-tag>
            <span v-else class="text-gray-400">否</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:station:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:station:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无站点，点击下方按钮添加第一个站点">
            <el-button type="primary" @click="openForm('create')">新增站点</el-button>
          </el-empty>
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <StationForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as StationApi from '@/api/transport/station'
import StationForm from './StationForm.vue'
import { useStationDictStore } from '@/store/modules/stationDict'

defineOptions({ name: 'TransportStation' })

const message = useMessage()
const stationDict = useStationDictStore()
const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref([])
type StationQueryParams = {
  pageNo: number
  pageSize: number
  stationCode?: string
  stationName?: string
  stationType?: string
  dispatchEnabled?: boolean
}

const queryParams = reactive<StationQueryParams>({
  pageNo: 1,
  pageSize: 10,
  stationCode: '',
  stationName: '',
  stationType: undefined,
  dispatchEnabled: undefined,
})
const formRef = ref()

const typeText = (t?: string) =>
  t === 'BUS_STOP' ? '公交站' : (t === 'MIXED' ? '混合站' : '货运站')

const getList = async () => {
  loading.value = true
  try {
    const res = await StationApi.getStationPage(queryParams)
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

const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, stationCode: '', stationName: '', stationType: undefined, dispatchEnabled: undefined })
  getList()
}
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该站点？'); await StationApi.deleteStation(id); stationDict.invalidate(); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}

onMounted(getList)
</script>
