<template>
  <Dialog title="订单发货" v-model="dialogVisible" width="500px">
    <el-form ref="formRef" :model="formData" label-width="100px" v-loading="formLoading">
      <el-form-item label="承运车辆">
        <el-select v-model="formData.vehicleId" placeholder="请选择承运车辆（可不选）" clearable filterable style="width:100%">
          <el-option v-for="v in vehicleOptions" :key="v.id!" :label="v.plateNo" :value="v.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="承运班次">
        <el-select v-model="formData.shiftId" placeholder="请选择承运班次（可不选）" clearable filterable style="width:100%">
          <el-option v-for="s in shiftOptions" :key="s.id!" :label="shiftLabel(s)" :value="s.id!" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确认发货</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as ProductOrderApi from '@/api/transport/productOrder'
import * as VehicleApi from '@/api/transport/vehicle'
import * as ShiftApi from '@/api/transport/shift'
import { Dialog } from '@/components/Dialog'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const emit = defineEmits(['success'])

const formData = ref<ProductOrderApi.ProductOrderShipReqVO>({
  id: 0,
  vehicleId: undefined,
  shiftId: undefined,
})

const vehicleOptions = ref<VehicleApi.VehicleVO[]>([])
const shiftOptions = ref<ShiftApi.ShiftVO[]>([])

const shiftLabel = (s: ShiftApi.ShiftVO) =>
  s.plannedDepartureTime ? `${s.shiftCode} ${s.plannedDepartureTime}` : s.shiftCode

const loadOptions = async () => {
  try {
    vehicleOptions.value = await VehicleApi.getSimpleVehicleList()
  } catch (e) { /* ignore */ }
  try {
    shiftOptions.value = await ShiftApi.getSimpleShiftList()
  } catch (e) { /* ignore */ }
}

const open = (id: number) => {
  dialogVisible.value = true
  formData.value = { id, vehicleId: undefined, shiftId: undefined }
  formRef.value?.resetFields()
  loadOptions()
}

defineExpose({ open })

const submitForm = async () => {
  formLoading.value = true
  try {
    await ProductOrderApi.shipProductOrder(formData.value)
    message.success('发货成功')
    dialogVisible.value = false
    emit('success')
  } finally {
    formLoading.value = false
  }
}
</script>
