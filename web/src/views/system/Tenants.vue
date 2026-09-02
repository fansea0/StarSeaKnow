<template>
  <section class="tenants-page" aria-labelledby="tenants-title">
    <header class="page-heading">
      <div>
        <span class="eyebrow">PLATFORM CONTROL / WORKSPACES</span>
        <h2 id="tenants-title">租户管理</h2>
        <p>管理工作区、查看成员，并控制登录安全。</p>
      </div>
      <el-button class="refresh-button" :loading="loading" @click="loadTenants">刷新列表</el-button>
    </header>

    <section class="tenant-stats" aria-label="租户概览">
      <article class="stat-card stat-card--accent"><span class="stat-label">注册租户</span><strong>{{ total }}</strong><span class="stat-note">已完成注册</span></article>
      <article class="stat-card"><span class="stat-label">正常租户</span><strong>{{ activeCount }}</strong><span class="stat-note">可以正常登录</span></article>
      <article class="stat-card"><span class="stat-label">已停用</span><strong>{{ disabledCount }}</strong><span class="stat-note">暂不可用</span></article>
    </section>

    <section class="tenant-panel" aria-label="租户筛选和列表">
      <div class="panel-intro"><div><h3>已注册工作区</h3><p>选择一个租户查看用户，并要求指定用户下次登录修改密码。</p></div><span class="panel-count">{{ total }} 个工作区</span></div>
      <form class="filters" @submit.prevent="applyFilters">
        <el-input v-model="filters.keyword" clearable placeholder="搜索租户名称或代码" aria-label="搜索租户" @clear="applyFilters" />
        <el-select v-model="filters.status" clearable placeholder="全部状态" aria-label="按状态筛选"><el-option label="正常" :value="1" /><el-option label="已停用" :value="0" /></el-select>
        <el-button class="filter-button" native-type="submit">筛选</el-button><el-button text @click="resetFilters">重置</el-button>
      </form>
      <p v-if="error" class="error-message" role="alert">{{ error }}</p>
      <div class="table-wrap" :aria-busy="loading">
        <table class="tenant-table">
          <thead><tr><th>租户</th><th>租户代码</th><th>状态</th><th>创建时间</th><th>平台备注</th><th class="actions-col">操作</th></tr></thead>
          <tbody>
            <tr v-for="tenant in tenants" :key="tenant.id">
              <td><div class="tenant-cell"><span class="tenant-avatar">{{ tenant.name?.slice(0, 1) || '租' }}</span><div><strong>{{ tenant.name }}</strong><small>{{ tenant.status === 1 ? '工作区运行中' : '工作区已暂停' }}</small></div></div></td>
              <td><code>{{ tenant.code }}</code></td>
              <td><el-tag :type="tenant.status === 1 ? 'success' : 'info'" effect="plain">{{ statusLabel(tenant.status) }}</el-tag></td>
              <td class="date-cell">{{ formatTime(tenant.createTime) }}</td>
              <td class="remark-cell">{{ tenant.remark || '—' }}</td>
              <td class="actions-col"><el-button class="user-action" @click="openUsers(tenant)">查看用户 <span aria-hidden="true">↗</span></el-button><el-button text @click="openRemark(tenant)">编辑备注</el-button><el-button v-if="tenant.status === 1" text type="danger" @click="disableTenant(tenant)">停用</el-button><span v-else class="muted">不可用</span></td>
            </tr>
            <tr v-if="!loading && tenants.length === 0"><td colspan="6" class="empty-state">没有匹配的租户</td></tr>
          </tbody>
        </table>
      </div>
      <footer class="table-footer"><span>第 {{ page }} 页 · 共 {{ total }} 个工作区</span><el-pagination v-model:current-page="page" v-model:page-size="pageSize" layout="prev, pager, next" :total="total" :page-sizes="[10, 20, 50]" @current-change="loadTenants" @size-change="changePageSize" /></footer>
    </section>

    <el-drawer v-model="usersDialogVisible" direction="rtl" size="min(560px, 100vw)" :with-header="false" class="users-drawer">
      <div class="drawer-head"><div><span class="eyebrow">WORKSPACE MEMBERS</span><h3>{{ selectedTenant?.name || '用户管理' }}</h3><code>{{ selectedTenant?.code }}</code></div><el-button circle text aria-label="关闭用户管理" @click="usersDialogVisible = false">×</el-button></div>
      <div class="drawer-summary"><span>租户用户</span><strong>{{ tenantUsers.length }} 人</strong><p>要求改密不会改变当前密码，用户下次登录时完成更新。</p></div>
      <div v-loading="usersLoading" class="user-list">
        <article v-for="user in tenantUsers" :key="user.id" class="user-row">
          <span class="user-avatar">{{ user.username?.slice(0, 1)?.toUpperCase() || 'U' }}</span><div class="user-info"><strong>{{ user.username }}</strong><span>{{ user.displayName || '未设置显示名' }} · {{ roleLabel(user.role) }}</span></div><div class="user-status"><span :class="['status-dot', { 'status-dot--warning': user.mustChangePassword }]" />{{ user.mustChangePassword ? '下次登录需改密' : '密码正常' }}</div><el-button v-if="!user.mustChangePassword" size="small" type="warning" plain @click="resetUser(user)">要求改密</el-button><el-tag v-else size="small" type="warning" effect="plain">已设置</el-tag>
        </article>
        <div v-if="!usersLoading && tenantUsers.length === 0" class="drawer-empty">该租户还没有用户。</div>
      </div>
    </el-drawer>

    <el-dialog v-model="remarkDialogVisible" title="编辑平台备注" width="480px" :close-on-click-modal="false"><p class="dialog-context">{{ selectedTenant?.name }} · 此信息仅在平台管理端可见。</p><el-form label-position="top" @submit.prevent="saveRemark(selectedTenant)"><el-form-item label="内部备注"><el-input v-model.trim="remarkForm.remark" type="textarea" :rows="4" maxlength="512" show-word-limit placeholder="例如：合同续约时间、运营跟进事项" /></el-form-item></el-form><template #footer><el-button @click="remarkDialogVisible = false">取消</el-button><el-button type="primary" :loading="savingRemark" @click="saveRemark(selectedTenant)">保存备注</el-button></template></el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '../../api/http'

const tenants = ref([]), total = ref(0), page = ref(1), pageSize = ref(20), loading = ref(false), error = ref('')
const remarkDialogVisible = ref(false), savingRemark = ref(false), selectedTenant = ref(null)
const usersDialogVisible = ref(false), usersLoading = ref(false), tenantUsers = ref([])
const filters = reactive({ keyword: '', status: undefined }), remarkForm = reactive({ remark: '' })
const activeCount = computed(() => tenants.value.filter((tenant) => tenant.status === 1).length)
const disabledCount = computed(() => tenants.value.filter((tenant) => tenant.status === 0).length)
function requestParams() { return { page: page.value, pageSize: pageSize.value, keyword: filters.keyword || undefined, status: filters.status } }
async function loadTenants() { loading.value = true; error.value = ''; try { const response = await http.get('/platform/tenants', { params: requestParams() }); const data = response.data?.data || {}; tenants.value = data.items || []; total.value = data.total || 0; page.value = data.page || page.value; pageSize.value = data.pageSize || pageSize.value } catch (cause) { error.value = cause.response?.data?.msg || '租户列表暂时无法加载' } finally { loading.value = false } }
async function applyFilters() { page.value = 1; await loadTenants() }
async function resetFilters() { filters.keyword = ''; filters.status = undefined; await applyFilters() }
async function changePageSize(size) { pageSize.value = size; page.value = 1; await loadTenants() }
function openRemark(tenant) { selectedTenant.value = tenant; remarkForm.remark = tenant.remark || ''; remarkDialogVisible.value = true }
async function openUsers(tenant) { selectedTenant.value = tenant; usersDialogVisible.value = true; usersLoading.value = true; try { const response = await http.get(`/platform/tenants/${tenant.id}/users`); tenantUsers.value = response.data?.data || [] } catch (cause) { usersDialogVisible.value = false; ElMessage.error(cause.response?.data?.msg || '用户列表加载失败') } finally { usersLoading.value = false } }
async function resetUser(user) { try { await ElMessageBox.confirm(`将要求用户“${user.username}”下次登录时修改密码，当前密码不变。`, '确认要求修改密码', { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' }) } catch { return } try { await http.post(`/platform/tenants/${selectedTenant.value.id}/users/${user.id}/reset-password`); user.mustChangePassword = true; ElMessage.success('已设置为下次登录修改密码') } catch (cause) { ElMessage.error(cause.response?.data?.msg || '设置失败') } }
async function saveRemark(tenant) { if (!tenant) return; savingRemark.value = true; try { await http.patch(`/platform/tenants/${tenant.id}/remark`, { remark: remarkForm.remark || null }); tenant.remark = remarkForm.remark || null; remarkDialogVisible.value = false; ElMessage.success('平台备注已保存') } catch (cause) { ElMessage.error(cause.response?.data?.msg || '保存备注失败') } finally { savingRemark.value = false } }
async function disableTenant(tenant) { try { await ElMessageBox.confirm(`停用“${tenant.name}”后，该租户将无法登录。`, '确认停用租户', { confirmButtonText: '确认停用', cancelButtonText: '取消', type: 'warning' }) } catch { return } try { await http.post(`/platform/tenants/${tenant.id}/disable`); ElMessage.success('租户已停用'); await loadTenants() } catch (cause) { ElMessage.error(cause.response?.data?.msg || '停用失败') } }
function statusLabel(status) { return status === 1 ? '正常' : '已停用' }
function roleLabel(role) { return role === 'tenant_admin' ? '租户管理员' : '成员' }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false, year: 'numeric', month: '2-digit', day: '2-digit' }) : '—' }
onMounted(loadTenants)
</script>

<style scoped>
.tenants-page { --page-ink: #142b3d; --page-muted: #6c7f8d; --page-line: #d9e5e7; max-width: 1440px; margin: 0 auto; padding: 8px 4px 40px; color: var(--page-ink); }
.page-heading { align-items: flex-start; margin-bottom: 26px; }.eyebrow { display: block; color: var(--sea-signal); font-size: 11px; font-weight: 800; letter-spacing: .16em; }.page-heading h2 { margin: 9px 0 5px; font-family: 'Noto Serif SC', serif; font-size: clamp(28px, 3vw, 40px); letter-spacing: -.04em; }.page-heading p { margin: 0; color: var(--page-muted); font-size: 15px; }.refresh-button { border-color: var(--page-line); color: var(--page-ink); background: #fff; }
.tenant-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }.stat-card { min-height: 116px; padding: 19px 22px; border: 1px solid var(--page-line); border-radius: 12px; background: rgb(255 255 255 / 78%); }.stat-card--accent { border-color: #a5d8d8; background: linear-gradient(135deg, #f3ffff, #fff); }.stat-label, .stat-note { display: block; color: var(--page-muted); font-size: 13px; }.stat-card strong { display: block; margin: 8px 0 2px; color: var(--page-ink); font-family: 'Noto Serif SC', serif; font-size: 30px; line-height: 1; }.stat-note { font-size: 12px; }
.tenant-panel { overflow: hidden; border: 1px solid var(--page-line); border-radius: 14px; background: #fff; box-shadow: 0 14px 35px rgb(17 36 59 / 6%); }.panel-intro { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 22px 24px 15px; }.panel-intro h3 { margin: 0 0 5px; font-size: 17px; }.panel-intro p { margin: 0; color: var(--page-muted); font-size: 13px; }.panel-count { color: var(--sea-signal); font-size: 13px; font-weight: 700; }.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; padding: 13px 24px 18px; border-bottom: 1px solid #edf2f3; background: #fbfdfd; }.filters :deep(.el-input) { width: min(330px, 100%); }.filters :deep(.el-select) { width: 150px; }.filter-button { color: #fff; background: var(--sea-signal); border-color: var(--sea-signal); }.error-message { margin: 14px 24px; color: var(--sea-danger); }.table-wrap { overflow-x: auto; }.tenant-table { width: 100%; min-width: 980px; border-collapse: collapse; font-size: 13px; }.tenant-table th { padding: 12px 24px; border-bottom: 1px solid var(--page-line); color: #78909b; font-size: 11px; font-weight: 800; letter-spacing: .08em; text-align: left; white-space: nowrap; }.tenant-table td { padding: 15px 24px; border-bottom: 1px solid #edf2f3; color: #405968; vertical-align: middle; }.tenant-table tr:last-child td { border-bottom: 0; }.tenant-table tr:hover td { background: #f8fcfc; }.tenant-cell { display: flex; align-items: center; gap: 11px; min-width: 180px; }.tenant-avatar, .user-avatar { display: inline-grid; flex: 0 0 auto; place-items: center; width: 34px; height: 34px; border-radius: 10px; color: #087d80; background: #dff4f1; font-weight: 800; }.tenant-cell strong, .tenant-cell small { display: block; }.tenant-cell strong { color: var(--page-ink); font-size: 14px; }.tenant-cell small { margin-top: 3px; color: #8a9aa3; font-size: 11px; }.tenant-table code, .drawer-head code { color: #39727b; font-family: 'JetBrains Mono', monospace; font-size: 12px; }.date-cell { white-space: nowrap; color: #687e89 !important; }.remark-cell { max-width: 190px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #758891 !important; }.actions-col { text-align: right !important; white-space: nowrap; }.user-action { border-color: #91cecc; color: #087d80; background: #effafa; font-weight: 700; }.user-action span { margin-left: 5px; }.muted { color: #9aa8ad; }.table-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 15px 24px; color: #81929a; font-size: 12px; }.empty-state { padding: 45px 24px !important; color: #8b9ba2 !important; text-align: center; }
.drawer-head { display: flex; align-items: flex-start; justify-content: space-between; padding-bottom: 22px; border-bottom: 1px solid #e6eeee; }.drawer-head h3 { margin: 8px 0 6px; font-family: 'Noto Serif SC', serif; font-size: 26px; }.drawer-summary { margin: 22px 0 14px; padding: 15px 17px; border-radius: 10px; background: #f1f9f8; }.drawer-summary span, .drawer-summary strong { display: inline-block; }.drawer-summary span { color: #688088; font-size: 13px; }.drawer-summary strong { margin-left: 8px; color: var(--sea-signal); }.drawer-summary p { margin: 7px 0 0; color: #71878e; font-size: 12px; line-height: 1.5; }.user-list { min-height: 120px; }.user-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto auto; align-items: center; gap: 11px; padding: 15px 0; border-bottom: 1px solid #edf2f2; }.user-avatar { width: 36px; height: 36px; border-radius: 50%; color: #3c5f9b; background: #e6edfc; }.user-info strong, .user-info span { display: block; }.user-info strong { color: var(--page-ink); font-size: 14px; }.user-info span { margin-top: 4px; overflow: hidden; color: #89999f; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.user-status { display: flex; align-items: center; gap: 6px; color: #72868d; font-size: 11px; white-space: nowrap; }.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #3bb28d; }.status-dot--warning { background: #e29d45; }.drawer-empty { padding: 40px 0; color: #81939a; text-align: center; }.dialog-context { margin: 0 0 12px; color: var(--page-muted); font-size: 13px; }
@media (max-width: 720px) { .tenants-page { padding-inline: 0; }.tenant-stats { grid-template-columns: 1fr; }.stat-card { min-height: 92px; }.panel-intro, .table-footer { align-items: flex-start; flex-direction: column; }.filters { padding-inline: 16px; }.panel-intro { padding-inline: 16px; }.tenant-table th, .tenant-table td { padding-inline: 16px; }.page-heading { flex-direction: column; }.user-row { grid-template-columns: auto minmax(0, 1fr) auto; }.user-status { grid-column: 2 / 3; }.user-row .el-button, .user-row .el-tag { grid-column: 3 / 4; grid-row: 1 / 3; } }
@media (prefers-reduced-motion: reduce) { .tenant-table tr { transition: none; } }
</style>
