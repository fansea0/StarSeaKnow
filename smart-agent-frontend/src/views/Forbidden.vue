<template>
  <el-result icon="warning" title="403" sub-title="您没有权限访问该资源">
    <template #extra>
      <el-button @click="$router.push('/')">返回首页</el-button>
    </template>
  </el-result>
</template>
