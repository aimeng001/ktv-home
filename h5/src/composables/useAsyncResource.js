import { ref } from 'vue'

/**
 * Track an asynchronous read without confusing an empty success with a
 * failed request. A failed refresh keeps the last successful value available
 * while exposing the error to the view.
 */
export function useAsyncResource(loader, initialValue = null) {
  const data = ref(initialValue)
  const status = ref('idle')
  const error = ref(null)
  let requestGeneration = 0

  async function load(...args) {
    const generation = ++requestGeneration
    status.value = 'loading'
    error.value = null
    try {
      const nextValue = await loader(...args)
      if (generation !== requestGeneration) return undefined
      data.value = nextValue
      status.value = 'success'
      return data.value
    } catch (cause) {
      if (generation !== requestGeneration) return undefined
      error.value = cause
      status.value = 'error'
      return undefined
    }
  }

  return { data, status, error, load }
}
