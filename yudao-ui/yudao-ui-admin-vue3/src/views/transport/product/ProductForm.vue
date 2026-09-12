<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px" v-loading="formLoading">
      <el-form-item label="商品名称" prop="name">
        <el-input v-model="formData.name" placeholder="请输入商品名称" />
      </el-form-item>
      <el-form-item label="产地村庄" prop="fromVillage">
        <el-input v-model="formData.fromVillage" placeholder="请输入产地村庄" />
      </el-form-item>
      <el-form-item label="售价" prop="price">
        <el-input-number v-model="formData.price" :precision="2" :min="0" :max="999999" style="width:100%" />
      </el-form-item>
      <el-form-item label="单位" prop="unit">
        <el-select v-model="formData.unit" placeholder="请选择单位" style="width:100%">
          <el-option v-for="u in unitList" :key="u" :label="u" :value="u" />
        </el-select>
      </el-form-item>
      <el-form-item label="商品图" prop="image">
        <div style="width:100%">
          <UploadFile v-model:model-value="formData.imageUrl" :limit="1" :file-type="['image']" :is-show-tip="false" />
          <el-input v-model="formData.image" placeholder="备用 emoji（未上传图片时显示），如 🍑" style="margin-top:8px" />
        </div>
      </el-form-item>
      <el-form-item label="角标" prop="badge">
        <el-input v-model="formData.badge" placeholder="如：大巴直通车" />
      </el-form-item>
      <el-form-item label="商品描述" prop="description">
        <el-input v-model="formData.description" type="textarea" :rows="3" placeholder="请输入商品描述" />
      </el-form-item>
      <el-form-item label="库存" prop="stock">
        <el-input-number v-model="formData.stock" :min="0" style="width:100%" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="formData.status">
          <el-radio :label="0">上架</el-radio>
          <el-radio :label="1">下架</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="排序" prop="sort">
        <el-input-number v-model="formData.sort" :min="0" style="width:100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取 消</el-button>
      <el-button type="primary" :loading="formLoading" @click="submitForm">确 定</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as ProductApi from '@/api/transport/product'
import { Dialog } from '@/components/Dialog'
import UploadFile from '@/components/UploadFile/src/UploadFile.vue'

const message = useMessage()
const formLoading = ref(false)
const dialogVisible = ref(false)
const dialogTitle = ref('')
const formType = ref('')
const formRef = ref()
const emit = defineEmits(['success'])
const unitList = ['斤', '箱', '袋', '盒', '瓶', '件']

const formData = ref<any>({
  name: '',
  fromVillage: '',
  price: 0,
  unit: '斤',
  image: '',
  imageUrl: '',
  badge: '',
  description: '',
  stock: 0,
  status: 0,
  sort: 0,
})

const formRules = reactive({
  name: [{ required: true, message: '商品名称不能为空', trigger: 'blur' }],
})

const resetForm = () => {
  formData.value = { name: '', fromVillage: '', price: 0, unit: '斤', image: '', imageUrl: '', badge: '', description: '', stock: 0, status: 0, sort: 0 }
  formRef.value?.resetFields()
}

const open = (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = type === 'create' ? '新增商品' : '编辑商品'
  formType.value = type
  resetForm()
  if (id) {
    formLoading.value = true
    ProductApi.getProduct(id).then((data) => { formData.value = { ...formData.value, ...data }; formLoading.value = false })
  }
}

defineExpose({ open })

const submitForm = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return
  formLoading.value = true
  try {
    if (formType.value === 'create') await ProductApi.createProduct(formData.value)
    else await ProductApi.updateProduct(formData.value)
    message.success(formType.value === 'create' ? '创建成功' : '更新成功')
    dialogVisible.value = false
    emit('success')
  } finally { formLoading.value = false }
}
</script>
