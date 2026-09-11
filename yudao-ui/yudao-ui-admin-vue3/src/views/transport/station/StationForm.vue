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
</template>

<script setup lang="ts">
import * as StationApi from '@/api/transport/station'
import { Dialog } from '@/components/Dialog'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])

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
