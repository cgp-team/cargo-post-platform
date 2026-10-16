<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="480px">
    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
      style="margin-bottom:12px"
    />
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px" v-loading="formLoading">
      <el-form-item label="班次编码" prop="shiftCode">
        <el-input v-model="formData.shiftCode" placeholder="请输入班次编码" />
      </el-form-item>
      <el-form-item label="所属线路" prop="routeId">
        <el-select v-model="formData.routeId" placeholder="请选择线路" style="width:100%">
          <el-option v-for="r in routeList" :key="r.id!" :label="r.routeName" :value="r.id!" />
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

const loadError = ref('')
const open = async (type: string, id?: number) => {
  dialogVisible.value = true
  loadError.value = ''
  dialogTitle.value = type === 'create' ? '新增班次' : '编辑班次'
  formType.value = type
  resetForm()
  loadRouteList()
  if (id) {
    formLoading.value = true
    try {
      const data = await ShiftApi.getShift(id)
      formData.value = { ...formData.value, ...data }
    } catch (e: any) {
      // 原来是 .then 且没有 reject 出口：读失败时表单停在默认值上，保存就把库里那行改掉
      loadError.value = '这条班次没读到：' + (e?.msg || e?.message || '未知原因') + '（表单里是默认值，别直接保存）'
    } finally {
      formLoading.value = false
    }
  }
}

const loadRouteList = async () => {
  try {
    routeList.value = await RouteApi.getSimpleRouteList()
  } catch (e: any) {
    routeList.value = []
    loadError.value = (loadError.value ? loadError.value + '；' : '') + '线路下拉没读到：' + (e?.msg || e?.message || '未知原因') + '（下拉空 ≠ 库里没有线路）'
  }
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
