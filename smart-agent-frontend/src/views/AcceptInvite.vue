<template><span aria-hidden="true" /></template>
<script setup>
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

onMounted(() => router.replace({ path: '/register', query: route.params.code ? { inviteCode: route.params.code } : undefined }))
</script>
