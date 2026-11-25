<template>
  <section class="bento-dashboard" aria-label="KTV 点歌大厅">
    <button
      v-for="tile in tiles"
      :key="tile.id"
      type="button"
      class="bento-tile"
      :class="`area-${tile.area}`"
      @click="$emit('select-tile', tile)"
    >
      <Transition v-if="Array.isArray(tile.carousel) && tile.carousel.length" name="tile-photo-push">
        <img
          :key="carouselIndex % tile.carousel.length"
          class="tile-art"
          :src="tileArtwork(tile)"
          :alt="tile.title"
        />
      </Transition>
      <img
        v-else
        class="tile-art"
        :src="tileArtwork(tile)"
        :alt="tile.title"
      />
      <div class="tile-gradient" />
      <span class="tile-title" :class="`align-${tile.align}`">{{ tile.title }}</span>
    </button>
  </section>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { BENTO_TILES } from './ktvCatalogPresets'

defineEmits(['select-tile'])

const tiles = BENTO_TILES
const carouselIndex = ref(0)
let carouselTimer = null

function tileArtwork(tile) {
  if (Array.isArray(tile.carousel) && tile.carousel.length) {
    return tile.carousel[carouselIndex.value % tile.carousel.length]
  }
  return tile.artwork
}

onMounted(() => {
  if (typeof Image !== 'undefined') {
    for (const tile of tiles) {
      if (!Array.isArray(tile.carousel)) continue
      for (const src of tile.carousel) {
        const img = new Image()
        img.src = src
      }
    }
  }
  carouselTimer = setInterval(() => {
    if (typeof document !== 'undefined' && document.hidden) return
    carouselIndex.value = (carouselIndex.value + 1) % 3
  }, 3500)
})

onBeforeUnmount(() => {
  if (carouselTimer) {
    clearInterval(carouselTimer)
    carouselTimer = null
  }
})
</script>

<style scoped>
/* 1:1 Android TV view_ktv_bento_dashboard.xml 5列6行比例栅格 */
.bento-dashboard {
  width: 100%;
  height: 100%;
  padding: clamp(6px, 0.8vh, 10px) clamp(14px, 1.4vw, 24px) clamp(8px, 1vh, 12px);
  display: grid;
  grid-template-columns: 18.9fr 31.1fr 18.8fr 15.6fr 15.6fr;
  grid-template-rows: repeat(6, 1fr);
  grid-template-areas:
    "hot singer opera featured featured"
    "hot singer opera featured featured"
    "hot singer language featured featured"
    "tiktok songname language featured featured"
    "tiktok songname theme favorites local"
    "tiktok songname theme favorites local";
  gap: clamp(8px, 0.7vw, 12px);
}

.area-hot { grid-area: hot; }
.area-tiktok { grid-area: tiktok; }
.area-singer { grid-area: singer; }
.area-songname { grid-area: songname; }
.area-opera { grid-area: opera; }
.area-language { grid-area: language; }
.area-theme { grid-area: theme; }
.area-featured { grid-area: featured; }
.area-favorites { grid-area: favorites; }
.area-local { grid-area: local; }

.bento-tile {
  position: relative;
  border-radius: 10px;
  overflow: hidden;
  border: 2px solid transparent;
  background: rgba(20, 26, 40, 0.72);
  box-shadow: 0 10px 26px rgba(0, 0, 0, 0.35);
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
  text-align: left;
}
.bento-tile:hover,
.bento-tile:focus-visible {
  transform: scale(1.018);
  border-color: var(--iktv-blue);
  box-shadow: 0 0 0 2px var(--iktv-blue-glow), 0 16px 36px rgba(0, 0, 0, 0.55);
  z-index: 2;
  outline: none;
}
.tile-art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  pointer-events: none;
}
/* 双拼人物底图沿中缝精准对齐，避免宽屏裁剪截断人物 */
.area-singer .tile-art {
  object-position: center bottom;
}
.area-songname .tile-art {
  object-position: center top;
}
.tile-photo-push-enter-active,
.tile-photo-push-leave-active {
  transition: transform 1s cubic-bezier(0.25, 0.46, 0.45, 0.94);
  will-change: transform;
}
.tile-photo-push-enter-from {
  transform: translate3d(0, 100%, 0);
}
.tile-photo-push-enter-to,
.tile-photo-push-leave-from {
  transform: translate3d(0, 0, 0);
}
.tile-photo-push-leave-to {
  transform: translate3d(0, -100%, 0);
}
@media (prefers-reduced-motion: reduce) {
  .tile-photo-push-enter-active,
  .tile-photo-push-leave-active {
    transition: none;
  }
}
.tile-gradient {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 72%, rgba(0, 0, 0, 0.24) 100%);
  pointer-events: none;
}
.tile-title {
  position: absolute;
  bottom: 12px;
  left: 16px;
  right: 16px;
  font-size: clamp(18px, 1.35vw, 24px);
  font-weight: 800;
  font-style: italic;
  letter-spacing: 0.5px;
  color: #fff;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.55);
}
.tile-title.align-center { text-align: center; }
.tile-title.align-start { text-align: left; }
.tile-title.align-end { text-align: right; }

@media (max-width: 899px) {
  .bento-dashboard {
    height: auto;
    padding: 10px;
    grid-template-columns: repeat(2, 1fr);
    grid-template-rows: none;
    grid-auto-rows: 112px;
    grid-template-areas:
      "singer songname"
      "hot tiktok"
      "language theme"
      "opera featured"
      "favorites local";
  }
  .tile-title {
    font-size: 16px;
    bottom: 9px;
  }
}
</style>
