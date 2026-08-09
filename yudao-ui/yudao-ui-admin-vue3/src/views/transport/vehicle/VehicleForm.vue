<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="车牌号" prop="plateNo">
        <el-input v-model="formData.plateNo" placeholder="请输入车牌号" />
      </el-form-item>
      <el-form-item label="车辆类型" prop="vehicleType">
        <el-input-number v-model="formData.vehicleType" :min="0" style="width:100%" />
      </el-form-item>
      <el-form-item label="载客人数" prop="passengerCapacity">
        <el-input-number v-model="formData.passengerCapacity" :min="0" style="width:100%" />
      </el-form-item>
      <el-form-item label="载货重量(kg)" prop="cargoCapacityKg">
        <el-input v-model.number="formData.cargoCapacityKg" placeholder="请输入载货重量(kg)" />
      </el-form-item>
      <el-form-item label="货仓件数" prop="cargoCapacity">
        <el-input-number v-model="formData.cargoCapacity" :min="1" style="width:100%" placeholder="货仓件数上限" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as VehicleApi from '@/api/transport/vehicle'
import { Dialog } from '@/components/Dialog'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])

const formData = ref<VehicleApi.VehicleVO>({
  plateNo: '',
  vehicleType: undefined,
  passengerCapacity: undefined,
  cargoCapacityKg: undefined,
  cargoCapacity: 4,
})

const formRules = reactive({
  plateNo: [{ required: true, message: '车牌号不能为空', trigger: 'blur' }],
})

const resetForm = () => {
  formData.value = { plateNo: '', vehicleType: undefined, passengerCapacity: undefined, cargoCapacityKg: undefined, cargoCapacity: 4 }
  formRef.value?.resetFields()
}

const open = (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增车辆' : '编辑车辆'
  formType.value = type
  resetForm()
  if (id) {
    formLoading.value = true
    VehicleApi.getVehicle(id).then((data) => { formData.value = data; formLoading.value = false })
  }
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  formLoading.value = true
  try {
    if (formType.value === 'create') await VehicleApi.createVehicle(formData.value)
    else await VehicleApi.updateVehicle(formData.value)
    message.success(formType.value === 'create' ? '创建成功' : '更新成功')
    dialogVisible.value = false
    emit('success')
  } finally { formLoading.value = false }
}
</script>
