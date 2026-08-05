<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px" v-loading="formLoading">
      <el-form-item label="班次编码" prop="shiftCode">
        <el-input v-model="formData.shiftCode" placeholder="请输入班次编码" />
      </el-form-item>
      <el-form-item label="所属线路" prop="routeId">
        <el-select v-model="formData.routeId" placeholder="请选择线路" style="width:100%">
          <el-option v-for="r in routeList" :key="r.id" :label="r.routeName" :value="r.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="计划发车时间" prop="plannedDepartureTime">
        <el-time-picker v-model="formData.plannedDepartureTime" format="HH:mm:ss" value-format="HH:mm:ss" style="width:100%" />
      </el-form-item>
      <el-form-item label="计划时长(分钟)" prop="plannedDurationMinutes">
        <el-input-number v-model="formData.plannedDurationMinutes" :min="1" style="width:100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as ShiftApi from '@/api/transport/shift'
import * as RouteApi from '@/api/transport/route'
import { Dialog } from '@/components/Dialog'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])
const routeList = ref<RouteApi.RouteVO[]>([])

const formData = ref<any>({
  shiftCode: '',
  routeId: null,
  plannedDepartureTime: null,
  plannedDurationMinutes: null,
})

const formRules = reactive({
  shiftCode: [{ required: true, message: '班次编码不能为空', trigger: 'blur' }],
  routeId: [{ required: true, message: '请选择线路', trigger: 'change' }],
})

const resetForm = () => {
  formData.value = { shiftCode: '', routeId: null, plannedDepartureTime: null, plannedDurationMinutes: null }
  formRef.value?.resetFields()
}

const open = (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增班次' : '编辑班次'
  formType.value = type
  resetForm()
  loadRouteList()
  if (id) {
    formLoading.value = true
    ShiftApi.getShift(id).then((data) => { formData.value = data; formLoading.value = false })
  }
}

const loadRouteList = async () => {
  try { routeList.value = await RouteApi.getSimpleRouteList() } catch (e) { /* ignore */ }
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  formLoading.value = true
  try {
    if (formType.value === 'create') await ShiftApi.createShift(formData.value)
    else await ShiftApi.updateShift(formData.value)
    message.success(formType.value === 'create' ? '创建成功' : '更新成功')
    dialogVisible.value = false
    emit('success')
  } finally { formLoading.value = false }
}
</script>
