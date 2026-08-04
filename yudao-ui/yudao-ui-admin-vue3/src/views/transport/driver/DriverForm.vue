<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="司机姓名" prop="name">
        <el-input v-model="formData.name" placeholder="请输入司机姓名" />
      </el-form-item>
      <el-form-item label="手机号" prop="mobile">
        <el-input v-model="formData.mobile" placeholder="请输入手机号" />
      </el-form-item>
      <el-form-item label="驾驶证号" prop="licenseNo">
        <el-input v-model="formData.licenseNo" placeholder="请输入驾驶证号" />
      </el-form-item>
      <el-form-item label="驾照到期日" prop="licenseExpireDate">
        <el-date-picker v-model="formData.licenseExpireDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as DriverApi from '@/api/transport/driver'
import { Dialog } from '@/components/Dialog'
const message = useMessage()
const formLoading = ref(false); const dialogVisible = ref(false); const dialogTitle = ref(''); const formType = ref('')
const formRef = ref(); const emit = defineEmits(['success'])
const formData = ref<DriverApi.DriverVO>({ name: '', mobile: '', licenseNo: '', licenseExpireDate: '' })
const formRules = reactive({ name: [{ required: true, message: '司机姓名不能为空', trigger: 'blur' }] })
const resetForm = () => { formData.value = { name: '', mobile: '', licenseNo: '', licenseExpireDate: '' }; formRef.value?.resetFields() }
const open = (type: string, id?: number) => {
  dialogVisible.value = true; dialogTitle.value = type === 'create' ? '新增司机' : '编辑司机'; formType.value = type; resetForm()
  if (id) { formLoading.value = true; DriverApi.getDriver(id).then((data) => { formData.value = data; formLoading.value = false }) }
}
defineExpose({ open })
const submitForm = async () => {
  const valid = await formRef.value?.validate(); if (!valid) return; formLoading.value = true
  try { if (formType.value === 'create') await DriverApi.createDriver(formData.value); else await DriverApi.updateDriver(formData.value)
    message.success(formType.value === 'create' ? '创建成功' : '更新成功'); dialogVisible.value = false; emit('success') } finally { formLoading.value = false }
}
</script>
