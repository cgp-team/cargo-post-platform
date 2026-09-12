<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="站点编码" prop="stationCode">
        <el-input v-model="formData.stationCode" placeholder="请输入站点编码" />
      </el-form-item>
      <el-form-item label="站点名称" prop="stationName">
        <el-input v-model="formData.stationName" placeholder="请输入站点名称" />
      </el-form-item>
      <el-form-item label="站点级别" prop="stationLevel">
        <el-input-number v-model="formData.stationLevel" :min="0" style="width:100%" />
      </el-form-item>
      <el-form-item label="经度" prop="longitude">
        <el-input v-model.number="formData.longitude" placeholder="请输入经度" />
      </el-form-item>
      <el-form-item label="纬度" prop="latitude">
        <el-input v-model.number="formData.latitude" placeholder="请输入纬度" />
      </el-form-item>
      <el-form-item label="坐标答疑">
        <el-button size="small" type="primary" plain @click="openMapPicker">地图选点</el-button>
        <span class="coord-tip">
          农村/园区没有现成公交站时，直接在地图上点选位置即可建站（GCJ-02，与高德、小程序一致）；
          也可以手工填写经纬度。
        </span>
      </el-form-item>
      <el-form-item label="站点地址" prop="address">
        <el-input v-model="formData.address" placeholder="请输入站点地址" />
      </el-form-item>
      <el-form-item label="站点状态" prop="status">
        <el-radio-group v-model="formData.status">
          <el-radio :label="0">启用</el-radio>
          <el-radio :label="1">停用</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="数据来源" prop="sourceType">
        <el-select v-model="formData.sourceType" placeholder="请选择" style="width:100%">
          <el-option label="项目自建站" value="PROJECT" />
          <el-option label="现实公交站" value="REAL" />
          <el-option label="模拟站" value="SIMULATION" />
        </el-select>
      </el-form-item>
      <el-form-item label="站点类型" prop="stationType">
        <el-select v-model="formData.stationType" placeholder="请选择" style="width:100%">
          <el-option label="货运站" value="CARGO_STATION" />
          <el-option label="公交站" value="BUS_STOP" />
          <el-option label="混合站" value="MIXED" />
        </el-select>
      </el-form-item>
      <el-form-item label="可达性" prop="userAccess">
        <el-checkbox v-model="formData.userAccess">用户可达（可推荐给用户送/取）</el-checkbox>
        <div style="width:100%">
          <el-checkbox v-model="formData.vehicleAccess">车辆可达（能进入装卸货）</el-checkbox>
        </div>
        <div style="width:100%">
          <el-checkbox v-model="formData.dispatchEnabled">开放调度（可作场站/换乘站）</el-checkbox>
        </div>
        <div style="width:100%;color:#909399;font-size:12px;line-height:1.5">
          新增站点默认「用户可达=是、车辆可达=否、开放调度=否」：站点创建后**立即**出现在地图与附近公交里，
          但不会自动加入线路、也不会自动获得车辆权限或调度资格，需要按需显式勾选。
        </div>
      </el-form-item>
      <el-form-item label="排序" prop="sort">
        <el-input-number v-model="formData.sort" :min="0" style="width:100%" />
      </el-form-item>
      <el-form-item label="备注" prop="remark">
        <el-input v-model="formData.remark" type="textarea" :rows="2" placeholder="如：校园禁行区，车辆不可进入" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>

  <!-- 地图选点：点击地图即取该点经纬度（内部 BD-09 → GCJ-02 转换，保证与业务坐标一致） -->
  <el-dialog v-model="mapPickerVisible" title="地图选点（点击地图选择站点位置）" width="720px" append-to-body>
    <div class="picker-tip">
      当前坐标：<b>{{ pickerLongitude?.toFixed(6) || '—' }}, {{ pickerLatitude?.toFixed(6) || '—' }}</b>
      <span class="coord-tip">（GCJ-02；点击地图任意位置即可选点）</span>
    </div>
    <div ref="pickerRef" class="picker-map"></div>
    <template #footer>
      <el-button @click="mapPickerVisible = false">取 消</el-button>
      <el-button type="primary" @click="applyPickerToForm">使用该坐标</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import * as StationApi from '@/api/transport/station'
import { Dialog } from '@/components/Dialog'
import { loadBaiduMapSdk } from '@/components/Map/src/utils'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])

// ==================== 地图选点（BD-09 ↔ GCJ-02） ====================
const X_PI = (Math.PI * 3000.0) / 180.0
/** GCJ-02 → BD-09（百度底图） */
const gcj02ToBd09 = (lng: number, lat: number) => {
  const z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI)
  const theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI)
  return { lng: z * Math.cos(theta) + 0.0065, lat: z * Math.sin(theta) + 0.006 }
}
/** BD-09 → GCJ-02（选点回填必须转回业务坐标系，否则站点会偏数百米） */
const bd09ToGcj02 = (bdLng: number, bdLat: number) => {
  const x = bdLng - 0.0065
  const y = bdLat - 0.006
  const z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI)
  const theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI)
  return { lng: z * Math.cos(theta), lat: z * Math.sin(theta) }
}

const mapPickerVisible = ref(false)
const pickerRef = ref<HTMLDivElement>()
const pickerLongitude = ref<number | undefined>()
const pickerLatitude = ref<number | undefined>()
let pickerMap: any = null
let pickerMarker: any = null

/** 打开选点地图：默认落在当前表单坐标（无坐标时落重庆主城） */
const openMapPicker = async () => {
  mapPickerVisible.value = true
  pickerLongitude.value = formData.value.longitude != null ? Number(formData.value.longitude) : undefined
  pickerLatitude.value = formData.value.latitude != null ? Number(formData.value.latitude) : undefined
  await nextTick()
  try {
    await loadBaiduMapSdk(10000)
  } catch {
    message.error('地图 SDK 加载失败，请检查网络后重试')
    return
  }
  const BMapGL = (window as any).BMapGL
  if (!BMapGL || !pickerRef.value) return
  const base = pickerLongitude.value != null && pickerLatitude.value != null
    ? gcj02ToBd09(pickerLongitude.value, pickerLatitude.value)
    : gcj02ToBd09(106.5765, 29.5325)
  if (!pickerMap) {
    pickerMap = new BMapGL.Map(pickerRef.value)
    pickerMap.enableScrollWheelZoom(true)
    pickerMap.addEventListener('click', (e: any) => {
      const gcj = bd09ToGcj02(e.latlng.lng, e.latlng.lat)
      pickerLongitude.value = Number(gcj.lng.toFixed(6))
      pickerLatitude.value = Number(gcj.lat.toFixed(6))
      if (pickerMarker) pickerMap.removeOverlay(pickerMarker)
      pickerMarker = new BMapGL.Marker(new BMapGL.Point(e.latlng.lng, e.latlng.lat))
      pickerMap.addOverlay(pickerMarker)
    })
  } else if (typeof pickerMap.resize === 'function') {
    pickerMap.resize()
  }
  pickerMap.centerAndZoom(new BMapGL.Point(base.lng, base.lat), 15)
  if (pickerMarker) pickerMap.removeOverlay(pickerMarker)
  pickerMarker = new BMapGL.Marker(new BMapGL.Point(base.lng, base.lat))
  pickerMap.addOverlay(pickerMarker)
}

const applyPickerToForm = () => {
  if (pickerLongitude.value == null || pickerLatitude.value == null) {
    message.warning('请先在地图上点击选择位置')
    return
  }
  formData.value.longitude = pickerLongitude.value
  formData.value.latitude = pickerLatitude.value
  mapPickerVisible.value = false
}

const formData = ref<StationApi.StationVO>({
  stationCode: '',
  stationName: '',
  stationLevel: undefined,
  longitude: undefined,
  latitude: undefined,
  address: '',
  status: 0,
  sourceType: 'PROJECT',
  stationType: 'CARGO_STATION',
  userAccess: true,
  vehicleAccess: false,
  dispatchEnabled: false,
  sort: 0,
  remark: '',
})

const formRules = reactive({
  stationCode: [{ required: true, message: '站点编码不能为空', trigger: 'blur' }],
  stationName: [{ required: true, message: '站点名称不能为空', trigger: 'blur' }],
})

const resetForm = () => {
  formData.value = {
    stationCode: '',
    stationName: '',
    stationLevel: undefined,
    longitude: undefined,
    latitude: undefined,
    address: '',
    status: 0,
    sourceType: 'PROJECT',
    stationType: 'CARGO_STATION',
    userAccess: true,
    vehicleAccess: false,
    dispatchEnabled: false,
    sort: 0,
    remark: '',
  }
  formRef.value?.resetFields()
}

const open = (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增站点' : '编辑站点'
  formType.value = type
  resetForm()
  if (id) {
    formLoading.value = true
    StationApi.getStation(id).then((data) => {
      formData.value = data
      formLoading.value = false
    })
  }
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  formLoading.value = true
  try {
    if (formType.value === 'create') {
      await StationApi.createStation(formData.value)
      message.success('创建成功')
    } else {
      await StationApi.updateStation(formData.value)
      message.success('更新成功')
    }
    dialogVisible.value = false
    emit('success')
  } finally {
    formLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.coord-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.picker-tip {
  font-size: 13px;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
}
.picker-map {
  width: 100%;
  height: 420px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  overflow: hidden;
}
</style>
