export function createMetadataReviewRequestGate() {
  let generation = 0

  return {
    begin(targetKey) {
      return { generation: ++generation, targetKey }
    },
    invalidate() {
      generation += 1
    },
    isCurrent(request, targetKey) {
      return request?.generation === generation && request.targetKey === targetKey
    },
  }
}
