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
