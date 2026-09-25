<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="480px">
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
      <el-form-item label="保险到期日" prop="insuranceExpireDate">
        <el-date-picker v-model="formData.insuranceExpireDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="请选择保险到期日" />
      </el-form-item>
      <el-form-item label="车辆状态" prop="status">
        <el-radio-group v-model="formData.status">
          <el-radio :value="0">可用</el-radio>
          <el-radio :value="1">停用</el-radio>
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
  status: 0,
})

// 民用车牌：省份简称 + 发牌机关字母 + 5 位序号；新能源为 6 位序号
const PLATE_PATTERN =
  /^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-Z][A-HJ-NP-Z0-9]{5,6}$/
const formRules = reactive({
  plateNo: [
    { required: true, message: '车牌号不能为空', trigger: 'blur' },
    { pattern: PLATE_PATTERN, message: '请输入正确的车牌号（如 渝A12345 / 渝AD12345）', trigger: 'blur' }
  ],
  // 载货重量用 el-input + .number，空值/非数字要拦住，否则后端收到 null 会按 0 处理
  cargoCapacityKg: [
    { type: 'number', min: 0, message: '载货重量需为不小于 0 的数字', trigger: 'blur' }
  ],
  cargoCapacity: [
    { required: true, message: '货仓件数不能为空', trigger: 'blur' },
    { type: 'number', min: 1, message: '货仓件数至少为 1', trigger: 'blur' }
  ],
  passengerCapacity: [
    { type: 'number', min: 0, message: '载客人数需为不小于 0 的数字', trigger: 'blur' }
  ],
})

const resetForm = () => {
  formData.value = { plateNo: '', vehicleType: undefined, passengerCapacity: undefined, cargoCapacityKg: undefined, cargoCapacity: 4, status: 0 }
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
