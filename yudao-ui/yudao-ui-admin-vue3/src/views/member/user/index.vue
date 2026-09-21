<template>
  <ContentWrap title="会员管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="昵称">
          <el-input v-model="queryParams.nickname" placeholder="请输入昵称" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="queryParams.mobile" placeholder="请输入手机号" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="注册时间">
          <el-date-picker
            v-model="queryParams.createTime"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" v-hasPermi="['member:user:query']" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-table v-loading="loading" :data="list" stripe border>
        <el-table-column label="会员" align="center" min-width="180">
          <template #default="scope">
            <div style="display:flex;align-items:center;gap:8px;justify-content:center">
              <el-avatar :src="scope.row.avatar" :size="32">
                {{ (scope.row.nickname || '会').slice(0, 1) }}
              </el-avatar>
              <span>{{ scope.row.nickname || '—' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="手机号" prop="mobile" align="center" width="140" />
        <el-table-column label="性别" align="center" width="70">
          <template #default="scope">{{ sexLabel(scope.row.sex) }}</template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="80">
          <template #default="scope">
            <el-tag :type="scope.row.status === 0 ? 'success' : 'danger'" size="small">
              {{ scope.row.status === 0 ? '正常' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="90" fixed="right">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['member:user:query']" @click="openDetail(scope.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>

  <!-- 会员详情抽屉：基础信息 + 收货地址 -->
  <el-drawer v-model="detailVisible" title="会员详情" size="480px">
    <div v-loading="detailLoading">
      <el-descriptions v-if="detail" :column="1" border size="small">
        <el-descriptions-item label="昵称">{{ detail.nickname || '—' }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ detail.mobile || '—' }}</el-descriptions-item>
        <el-descriptions-item label="性别">{{ sexLabel(detail.sex) }}</el-descriptions-item>
        <el-descriptions-item label="所在地">{{ detail.areaName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="会员等级">{{ detail.levelName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="最后登录时间">{{ detail.loginDate || '—' }}</el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ detail.createTime || '—' }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">收货地址</el-divider>
      <el-empty v-if="!addressList.length" :image-size="60" description="暂无收货地址" />
      <div v-else class="address-list">
        <div v-for="addr in addressList" :key="addr.id" class="address-item">
          <div class="address-head">
            <b>{{ addr.name }}</b>
            <span class="address-mobile">{{ addr.mobile }}</span>
            <el-tag v-if="addr.defaultStatus" type="success" size="small">默认</el-tag>
          </div>
          <div class="address-body">{{ (addr.areaName || '') + ' ' + (addr.detailAddress || '') }}</div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import * as MemberUserApi from '@/api/member/user'

defineOptions({ name: 'MemberUser' })

const loading = ref(true)
const total = ref(0)
const list = ref<MemberUserApi.MemberUserVO[]>([])

const queryParams = reactive<MemberUserApi.MemberUserPageReqVO>({
  pageNo: 1,
  pageSize: 10,
  nickname: '',
  mobile: '',
  createTime: undefined
})

const getList = async () => {
  loading.value = true
  try {
    const params = { ...queryParams }
    if (!params.createTime?.length) params.createTime = undefined
    const res = await MemberUserApi.getMemberUserPage(params)
    list.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}
const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, nickname: '', mobile: '', createTime: undefined })
  getList()
}

const sexLabel = (sex?: number) => (sex === 1 ? '男' : sex === 2 ? '女' : '未知')

/** 详情抽屉 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<MemberUserApi.MemberUserVO>()
const addressList = ref<MemberUserApi.MemberAddressVO[]>([])
const openDetail = async (row: MemberUserApi.MemberUserVO) => {
  if (!row.id) return
  detail.value = row
  addressList.value = []
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await MemberUserApi.getMemberUser(row.id)
    addressList.value = await MemberUserApi.getMemberAddressList(row.id)
  } catch (e) { /* 请求失败由 axios 统一提示 */ } finally {
    detailLoading.value = false
  }
}

onMounted(getList)
</script>

<style scoped>
.address-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.address-item {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 10px 12px;
}
.address-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.address-mobile {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.address-body {
  margin-top: 4px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}
</style>
