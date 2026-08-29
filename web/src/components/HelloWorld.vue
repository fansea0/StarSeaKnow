<script setup>
import { ref } from 'vue'

defineProps({
  msg: String,
})

const count = ref(0)
</script>

<template>
  <div class="fullscreen-container">
    <div class="content-wrapper">
      <h1 class="title">{{ msg }}</h1>

      <div class="card">
        <button type="button" class="counter-button" @click="count++">
          count is {{ count }}
        </button>
        <p class="edit-tip">
          Edit
          <code>components/HelloWorld.vue</code> to test HMR
        </p>
      </div>

      <div class="links">
        <p>
          Check out
          <a href="https://vuejs.org/guide/quick-start.html#local" target="_blank"
            >create-vue</a
          >, the official Vue + Vite starter
        </p>
        <p>
          Learn more about IDE Support for Vue in the
          <a
            href="https://vuejs.org/guide/scaling-up/tooling.html#ide-support"
            target="_blank"
            >Vue Docs Scaling up Guide</a
          >.
        </p>
      </div>
      <p class="read-the-docs">Click on the Vite and Vue logos to learn more</p>
    </div>
  </div>
</template>

<style scoped>
.fullscreen-container {
  min-height: 100vh;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
}

.content-wrapper {
  max-width: 1200px;
  width: 90%;
  padding: 4rem;
  text-align: center;
}

.title {
  font-size: 3.5rem;
  font-weight: 700;
  margin-bottom: 3rem;
  color: #2c3e50;
  background: linear-gradient(45deg, #42b883, #35495e);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
}

.card {
  background: rgba(255, 255, 255, 0.9);
  padding: 3rem;
  border-radius: 20px;
  box-shadow: 0 10px 20px rgba(0, 0, 0, 0.1);
  margin-bottom: 3rem;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
}

.card:hover {
  transform: translateY(-10px);
  box-shadow: 0 15px 30px rgba(0, 0, 0, 0.15);
}

.counter-button {
  background: #42b883;
  color: white;
  border: none;
  padding: 1rem 2rem;
  border-radius: 12px;
  font-size: 1.5rem;
  cursor: pointer;
  transition: all 0.3s ease;
  margin-bottom: 1.5rem;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.counter-button:hover {
  background: #3aa876;
  transform: scale(1.05);
  box-shadow: 0 6px 8px rgba(0, 0, 0, 0.15);
}

.edit-tip {
  color: #666;
  font-size: 1.1rem;
}

.edit-tip code {
  background: #f5f5f5;
  padding: 0.3rem 0.6rem;
  border-radius: 6px;
  font-family: monospace;
  font-size: 1.1rem;
}

.links {
  margin: 3rem 0;
}

.links p {
  font-size: 1.2rem;
  margin: 1rem 0;
}

.links a {
  color: #42b883;
  text-decoration: none;
  font-weight: 600;
  transition: all 0.3s ease;
  padding: 0.2rem 0.4rem;
  border-radius: 4px;
}

.links a:hover {
  color: #3aa876;
  background: rgba(66, 184, 131, 0.1);
  text-decoration: none;
}

.read-the-docs {
  color: #666;
  font-size: 1.1rem;
  margin-top: 3rem;
}

@media (max-width: 768px) {
  .content-wrapper {
    padding: 2rem;
  }

  .title {
    font-size: 2.5rem;
  }

  .card {
    padding: 2rem;
  }

  .counter-button {
    padding: 0.8rem 1.6rem;
    font-size: 1.2rem;
  }
}
</style>
