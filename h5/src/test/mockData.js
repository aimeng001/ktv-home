/**
 * 自动化测试数据工厂 —— 提供全面的模拟数据，覆盖所有页面和功能场景。
 */

export function createMockSong(overrides = {}) {
  return {
    id: 101,
    title: '晴天',
    artist: '周杰伦',
    artistKey: '周杰伦',
    mediaType: 'KTV_VIDEO',
    language: '国语',
    tags: ['流行', '经典'],
    pinyin: 'QT',
    filePath: '/music/周杰伦 - 晴天.mkv',
    importSource: 'TRANSCODED',
    playCount: 42,
    hasVocalTrack: true,
    audioTracks: 2,
    audioLayout: {
      layout: 'DUAL_TRACK',
      originalTrackIndex: 0,
      accompanimentTrackIndex: 1
    },
    durationMs: 269000,
    coverUrl: '/api/cover/101',
    ...overrides
  }
}

export function createMockQueueItem(overrides = {}) {
  return {
    queueId: 1001,
    songId: 101,
    orderedBy: 'user-token-abc',
    orderedByNick: '小明',
    orderedAt: Date.now() - 60000,
    song: createMockSong(),
    ...overrides
  }
}

export function createMockPlaylist(overrides = {}) {
  return {
    id: 'pl-1',
    name: '华语经典金曲',
    theme: '怀旧流行',
    description: '精选千禧年代经典流行歌曲',
    coverEmoji: '🎤',
    songCount: 15,
    publicVisible: true,
    isAiGenerated: false,
    songs: [createMockSong({ id: 101, title: '晴天' }), createMockSong({ id: 102, title: '七里香' })],
    ...overrides
  }
}

export function createMockArtist(overrides = {}) {
  return {
    artistKey: 'zhoujielun',
    name: '周杰伦',
    gender: '男歌手',
    artistKind: 'PERSON',
    reviewed: true,
    songCount: 38,
    avatarUrl: '/api/artist/zhoujielun/avatar',
    songs: [createMockSong({ id: 101, title: '晴天' })],
    ...overrides
  }
}

export function createMockWish(overrides = {}) {
  return {
    id: 1,
    keyword: '夜曲',
    status: 'OPEN',
    createdBy: 'client-token-123',
    createdAt: '2026-09-01T12:00:00Z',
    ...overrides
  }
}

export function createMockSourceItem(overrides = {}) {
  return {
    id: 501,
    sourceFilename: '周杰伦 - 晴天.mkv',
    sourcePath: '/raw/周杰伦 - 晴天.mkv',
    parsedTitle: '晴天',
    parsedArtist: '周杰伦',
    mediaType: 'KTV_VIDEO',
    videoCodec: 'h264',
    audioCodec: 'aac',
    sourceFormat: 'matroska',
    transcodeRequired: false,
    duplicate: false,
    displayStatus: 'PENDING_TRANSCODE',
    sourceDeleted: false,
    sourceMd5: 'a1b2c3d4e5f6',
    outputMd5: 'f6e5d4c3b2a1',
    outputPath: '/music/周杰伦 - 晴天.mkv',
    ...overrides
  }
}

export function createMockScrapeTask(overrides = {}) {
  return {
    batchId: 'scrape-batch-001',
    status: 'RUNNING',
    mode: 'ALL',
    total: 10,
    completed: 4,
    autoApplied: 2,
    review: 1,
    failed: 1,
    processing: 0,
    pending: 6,
    autoApplyThreshold: 0.95,
    createdAt: Date.now() - 120000,
    items: [
      {
        id: 1,
        songId: 101,
        title: '晴天',
        artist: '周杰伦',
        status: 'REVIEW',
        score: 0.88,
        provider: 'NETEASE',
        result: {
          track: {
            provider: 'NETEASE',
            externalId: '186016',
            title: '晴天',
            artists: ['周杰伦'],
            album: '叶惠美',
            releaseDate: '2003-07-31',
            coverUrl: 'https://p1.music.126.net/cover.jpg'
          }
        }
      }
    ],
    ...overrides
  }
}
