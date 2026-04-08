<template>
  <div class="user-manage">
    <div class="page-header">
      <h2 class="page-title">用户管理</h2>
    </div>

    <div class="card">
      <!-- 搜索栏 -->
      <div class="table-toolbar">
        <el-form :inline="true" :model="searchForm">
          <el-form-item label="用户名">
            <el-input v-model="searchForm.username" placeholder="请输入用户名" clearable />
          </el-form-item>
          <el-form-item label="角色">
            <el-select v-model="searchForm.role" placeholder="选择角色" clearable>
              <el-option label="管理员" value="admin" />
              <el-option label="普通用户" value="user" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleSearch">
              <el-icon><Search /></el-icon> 搜索
            </el-button>
            <el-button @click="handleReset">
              <el-icon><Refresh /></el-icon> 重置
            </el-button>
            <el-button type="success" @click="handleCreate">
              新增用户
            </el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- 用户表格 -->
      <el-table :data="userList" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="realName" label="真实姓名" width="120" />
        <el-table-column prop="email" label="邮箱" width="180" />
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column prop="role" label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="row.role === 'admin' ? 'danger' : 'info'">
              {{ row.role === 'admin' ? '管理员' : '用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-switch 
              v-model="row.status" 
              :active-value="1" 
              :inactive-value="0"
              @change="handleStatusChange(row)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" fixed="right" width="150">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div style="margin-top: 16px; text-align: right;">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- 用户对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="500px">
      <el-form ref="formRef" :model="editForm" :rules="formRules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="editForm.username" :disabled="!isCreateMode" />
        </el-form-item>
        <el-form-item v-if="isCreateMode" label="密码" prop="password">
          <el-input v-model="editForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="真实姓名">
          <el-input v-model="editForm.realName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editForm.email" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="editForm.phone" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="editForm.role">
            <el-option label="管理员" value="admin" />
            <el-option label="普通用户" value="user" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isCreateMode" label="状态">
          <el-select v-model="editForm.status">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, reactive, onMounted, nextTick } from 'vue'
import { getUserPage, createUser, updateUser, deleteUser, updateUserStatus } from '@/api/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'

const loading = ref(false)
const userList = ref([])
const dialogVisible = ref(false)
const formRef = ref(null)
const dialogMode = ref('edit')

const searchForm = reactive({ username: '', role: '' })
const pagination = reactive({ current: 1, size: 10, total: 0 })
const editForm = reactive({ id: null, username: '', password: '', realName: '', email: '', phone: '', role: 'user', status: 1 })

const isCreateMode = computed(() => dialogMode.value === 'create')
const dialogTitle = computed(() => isCreateMode.value ? '新增用户' : '编辑用户')

const formRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度为3-20位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20位', trigger: 'blur' }
  ],
  role: [
    { required: true, message: '请选择角色', trigger: 'change' }
  ]
}

const resetEditForm = () => {
  Object.assign(editForm, {
    id: null,
    username: '',
    password: '',
    realName: '',
    email: '',
    phone: '',
    role: 'user',
    status: 1
  })
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await getUserPage({
      current: pagination.current,
      size: pagination.size,
      ...searchForm
    })
    userList.value = res.data.records
    pagination.total = res.data.total
  } catch (error) {
    console.error('加载失败:', error)
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  pagination.current = 1
  loadData()
}

const handleReset = () => {
  searchForm.username = ''
  searchForm.role = ''
  handleSearch()
}

const handlePageChange = () => loadData()
const handleSizeChange = () => { pagination.current = 1; loadData() }

const handleStatusChange = async (row) => {
  try {
    await updateUserStatus(row.id, row.status)
    ElMessage.success('状态更新成功')
  } catch (error) {
    row.status = row.status === 1 ? 0 : 1
  }
}

const handleCreate = () => {
  dialogMode.value = 'create'
  resetEditForm()
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

const handleEdit = (row) => {
  dialogMode.value = 'edit'
  resetEditForm()
  Object.assign(editForm, row)
  editForm.password = ''
  dialogVisible.value = true
  nextTick(() => formRef.value?.clearValidate())
}

const handleSave = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  try {
    if (isCreateMode.value) {
      await createUser({
        username: editForm.username,
        password: editForm.password,
        realName: editForm.realName,
        email: editForm.email,
        phone: editForm.phone,
        role: editForm.role,
        status: editForm.status
      })
      ElMessage.success('创建成功')
    } else {
      await updateUser(editForm.id, {
        username: editForm.username,
        realName: editForm.realName,
        email: editForm.email,
        phone: editForm.phone,
        role: editForm.role
      })
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    resetEditForm()
    loadData()
  } catch (error) {
    console.error('保存失败:', error)
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确定删除该用户吗？', '警告', {
    type: 'warning'
  }).then(async () => {
    await deleteUser(row.id)
    ElMessage.success('删除成功')
    loadData()
  }).catch(() => {})
}

onMounted(() => loadData())
</script>

<style scoped lang="scss">
.user-manage {
  .table-toolbar {
    margin-bottom: 16px;
  }
}
</style>
