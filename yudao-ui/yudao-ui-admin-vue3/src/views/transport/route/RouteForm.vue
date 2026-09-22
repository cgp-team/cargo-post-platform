<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="线路编码" prop="routeCode">
        <el-input v-model="formData.routeCode" placeholder="请输入线路编码" />
      </el-form-item>
      <el-form-item label="线路名称" prop="routeName">
        <el-input v-model="formData.routeName" placeholder="请输入线路名称" />
      </el-form-item>
      <el-form-item label="起点站点" prop="startStationId">
        <el-select v-model="formData.startStationId" placeholder="请选择起点站点" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id!" :label="s.stationName" :value="s.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="终点站点" prop="endStationId">
        <el-select v-model="formData.endStationId" placeholder="请选择终点站点" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id!" :label="s.stationName" :value="s.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="里程(km)" prop="distanceKm">
        <el-input v-model.number="formData.distanceKm" placeholder="请输入里程(km)" />
      </el-form-item>
      <el-form-item label="数据来源" prop="sourceType">
        <el-select v-model="formData.sourceType" placeholder="请选择" style="width:100%">
          <el-option label="项目自建线路" value="PROJECT" />
          <el-option label="现实公交线路" value="REAL" />
        </el-select>
      </el-form-item>
      <el-form-item label="服务类型" prop="serviceType">
        <el-select v-model="formData.serviceType" placeholder="请选择" style="width:100%">
          <el-option label="货运" value="CARGO" />
          <el-option label="客运" value="PASSENGER" />
          <el-option label="客货邮混合" value="MIXED" />
        </el-select>
      </el-form-item>
      <el-form-item label="线路状态" prop="status">
        <el-radio-group v-model="formData.status">
          <el-radio :value="0">启用</el-radio>
          <el-radio :value="1">停用</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="开放调度" prop="dispatchEnabled">
        <el-switch v-model="formData.dispatchEnabled" />
        <span style="margin-left:8px;color:#909399;font-size:12px">停用/不开放调度的线路不会用于附近公交、地图与联运换乘</span>
      </el-form-item>

      <!-- 经停站序：农村/园区没有现成公交路网时，用「站点管理→地图选点」先建站，再在这里按顺序拼成自己的线路 -->
      <el-form-item v-if="formType === 'update'" label="经停站序">
        <div class="stops-editor">
          <div class="stops-tip">
            按顺序排列经停站点即为线路走向（起点 → 途经站 → 终点）；保存后线路立即参与附近公交、排班、调度与司机导航。
          </div>
          <div v-for="(s, i) in routeStations" :key="s.key" class="stops-row">
            <span class="stops-seq">{{ i + 1 }}</span>
            <el-select v-model="s.stationId" placeholder="选择站点" filterable style="flex:1">
              <el-option v-for="st in stationList" :key="st.id!" :label="st.stationName" :value="st.id!" />
            </el-select>
            <el-button link type="primary" :disabled="i === 0" @click="moveStation(i, -1)">上移</el-button>
            <el-button link type="primary" :disabled="i === routeStations.length - 1" @click="moveStation(i, 1)">下移</el-button>
            <el-button link type="danger" @click="removeStation(i)">删除</el-button>
          </div>
          <div class="stops-actions">
            <el-button size="small" type="primary" plain @click="addStation">添加经停站点</el-button>
            <el-button size="small" type="success" plain :loading="stopsSaving" @click="saveStations">保存站序</el-button>
            <el-button size="small" text @click="syncFromStartEnd">用起终点生成首尾两站</el-button>
          </div>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as RouteApi from '@/api/transport/route'
import * as StationApi from '@/api/transport/station'
import { Dialog } from '@/components/Dialog'
const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])
const stationList = ref<StationApi.StationVO[]>([])
/** 经停站序（编辑态可改）：key 仅用于列表渲染稳定性 */
const routeStations = ref<{ key: number; stationId?: number }[]>([])
const stopsSaving = ref(false)
let stopKeySeed = 1
const formData = ref<any>({
  routeCode: '', routeName: '', startStationId: null, endStationId: null, distanceKm: null,
  sourceType: 'PROJECT', serviceType: 'CARGO', status: 0, dispatchEnabled: true
})
// 起终点相同会产生"零长度线路"：里程为 0、算法代价矩阵退化，调度结果不可用——在入口拦住
const validateEndStation = (_rule: any, value: any, callback: any) => {
  if (value != null && value === formData.value.startStationId) {
    return callback(new Error('终点站点不能与起点站点相同'))
  }
  callback()
}
const formRules = reactive({
  routeCode: [{ required: true, message: '线路编码不能为空', trigger: 'blur' }],
  routeName: [{ required: true, message: '线路名称不能为空', trigger: 'blur' }],
  startStationId: [{ required: true, message: '请选择起点站点', trigger: 'change' }],
  endStationId: [
    { required: true, message: '请选择终点站点', trigger: 'change' },
    { validator: validateEndStation, trigger: 'change' }
  ],
  distanceKm: [{ type: 'number', min: 0, message: '里程需为不小于 0 的数字', trigger: 'blur' }],
})
const resetForm = () => {
  formData.value = {
    routeCode: '', routeName: '', startStationId: null, endStationId: null, distanceKm: null,
    sourceType: 'PROJECT', serviceType: 'CARGO', status: 0, dispatchEnabled: true
  }
  formRef.value?.resetFields()
}
const open = (type: string, id?: number) => {
  dialogVisible.value = true; dialogTitle.value = type === 'create' ? '新增线路' : '编辑线路'; formType.value = type; resetForm()
  loadStationList()
  routeStations.value = []
  if (id) {
    formLoading.value = true
    RouteApi.getRoute(id).then((data) => { formData.value = { ...formData.value, ...data }; formLoading.value = false })
    RouteApi.getRouteStations(id)
      .then((list) => {
        routeStations.value = (list || []).map((s) => ({ key: stopKeySeed++, stationId: s.stationId }))
      })
      .catch(() => { /* 站序拉取失败不影响线路基本信息编辑 */ })
  }
}

const addStation = () => {
  routeStations.value.push({ key: stopKeySeed++, stationId: undefined })
}
const removeStation = (index: number) => {
  routeStations.value.splice(index, 1)
}
const moveStation = (index: number, delta: number) => {
  const target = index + delta
  if (target < 0 || target >= routeStations.value.length) return
  const list = routeStations.value
  ;[list[index], list[target]] = [list[target], list[index]]
  routeStations.value = [...list]
}
/** 用起终点快速生成首尾两站（后续可插入途经站） */
const syncFromStartEnd = () => {
  const first = formData.value.startStationId
  const last = formData.value.endStationId
  const next: { key: number; stationId?: number }[] = []
  if (first) next.push({ key: stopKeySeed++, stationId: first })
  if (last && last !== first) next.push({ key: stopKeySeed++, stationId: last })
  if (next.length) routeStations.value = next
}

const saveStations = async () => {
  const stations = routeStations.value
    .filter((s) => s.stationId != null)
    .map((s) => ({ stationId: s.stationId as number }))
  if (!stations.length) {
    message.warning('请至少添加一个经停站点')
    return
  }
  stopsSaving.value = true
  try {
    await RouteApi.saveRouteStations({ routeId: formData.value.id, stations })
    message.success('站序已保存（里程与计划分钟已按站点自动更新）')
    const id = formData.value.id
    if (id) {
      const list = await RouteApi.getRouteStations(id).catch(() => [])
      routeStations.value = (list || []).map((s) => ({ key: stopKeySeed++, stationId: s.stationId }))
      const refreshed = await RouteApi.getRoute(id).catch(() => null)
      if (refreshed) formData.value = { ...formData.value, ...refreshed }
    }
    emit('success')
  } finally {
    stopsSaving.value = false
  }
}
const loadStationList = async () => {
  try { stationList.value = await StationApi.getSimpleStationList() } catch (e) { /* ignore */ }
}
defineExpose({ open })
const submitForm = async () => {
  const valid = await formRef.value?.validate(); if (!valid) return; formLoading.value = true
  try {
    if (formType.value === 'create') await RouteApi.createRoute(formData.value)
    else await RouteApi.updateRoute(formData.value)
    message.success(formType.value === 'create' ? '创建成功' : '更新成功'); dialogVisible.value = false; emit('success')
  } finally { formLoading.value = false }
}
</script>

<style lang="scss" scoped>
.stops-editor {
  width: 100%;
}
.stops-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-bottom: 6px;
}
.stops-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.stops-seq {
  width: 20px;
  text-align: right;
  color: var(--el-color-primary);
  font-weight: 600;
}
.stops-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
