#!/usr/bin/env python3
"""Loopback-only API/stream fixture for the ten approved Xiaomi-TV MV samples.

The fixture never copies, transcodes, or writes to media inputs. It serves only
the fixed numeric IDs below and logs API/control/progress observations locally.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import stat
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web


SAMPLE_ROOT = Path(
    r"D:\mimocode\ktv-home\参考\xiaomi-tv-evidence-20261106\songs"
)
SAMPLES = (
    ("60002434", "song1/60002434", 288_744, "720x480", "MP2"),
    ("60003120", "song1/60003120", 213_159, "720x480", "MP3"),
    ("60009718", "song1/60009718", 298_920, "704x480", "MP2"),
    ("60151801", "song16/60151801", 348_984, "720x576", "MP2"),
    ("60193628", "song20/60193628", 216_408, "720x480", "MP2"),
    ("60214337", "song22/60214337", 205_290, "1280x720", "AAC"),
    ("100000075", "song4001/100000075", 204_055, "1280x720", "AAC"),
    ("100001620", "song4001/100001620", 256_316, "1280x720", "AAC"),
    ("100003569", "song4001/100003569", 259_396, "1280x720", "AAC"),
    ("60073216", "song8/60073216", 257_390, "720x480", "MP3"),
)
SONG_IDS = {int(row[0]) for row in SAMPLES}
SONG_TO_FILE = {int(row[0]): 700_000 + index for index, row in enumerate(SAMPLES, start=1)}

REAL_SONG_METADATA = {
    60002434: {
        "title": "I BELIEVE",
        "artist": "孙楠",
        "acronym": "IB",
        "language": "国语",
        "tags": ["流行", "华语经典", "全能星战", "我是歌手", "高清HD歌曲"],
    },
    60003120: {
        "title": "JUST THE WAY YOU ARE",
        "artist": "BRUNO MARS",
        "acronym": "JTWYA",
        "language": "英语",
        "tags": ["流行", "欧美金曲", "高清HD歌曲"],
    },
    60009718: {
        "title": "白色恋人",
        "artist": "游鸿明",
        "acronym": "BSLR",
        "language": "国语",
        "tags": ["80后歌曲", "怀旧金曲", "流行"],
    },
    60073216: {
        "title": "FIRST LOVE",
        "artist": "宇多田ヒカル",
        "acronym": "FL",
        "language": "日语",
        "tags": ["怀旧", "日韩经典", "经典老歌"],
    },
    60151801: {
        "title": "我们的爱",
        "artist": "林俊杰",
        "acronym": "WMDA",
        "language": "国语",
        "tags": ["流行", "我是歌手", "中国好声音", "热门综艺", "高清HD歌曲"],
    },
    60193628: {
        "title": "莫问归期",
        "artist": "蒋雪儿",
        "acronym": "MWGQ",
        "language": "国语",
        "tags": ["流行", "抖音神曲", "古风"],
    },
    60214337: {
        "title": "虞兮叹",
        "artist": "闻人听书",
        "acronym": "YXT",
        "language": "国语",
        "tags": ["高清HD歌曲", "抖音神曲", "古风", "流行"],
    },
    100000075: {
        "title": "错位时空",
        "artist": "艾辰",
        "acronym": "CWSK",
        "language": "国语",
        "tags": ["流行", "抖音神曲", "网络热歌", "高清HD歌曲"],
    },
    100001620: {
        "title": "一路生花",
        "artist": "温奕心",
        "acronym": "YLSH",
        "language": "国语",
        "tags": ["流行", "抖音神曲", "热门新歌", "高清HD歌曲"],
    },
    100003569: {
        "title": "你一定要幸福",
        "artist": "虎二",
        "acronym": "NYDYXF",
        "language": "国语",
        "tags": ["流行", "热门新歌", "抒情", "高清HD歌曲"],
    },
}


@dataclass(frozen=True)
class Sample:
    song_id: int
    file_id: int
    relative_path: str
    duration_ms: int
    resolution: str
    audio_codec: str
    path: Path
    size: int

    @property
    def meta(self) -> dict[str, Any]:
        return REAL_SONG_METADATA.get(self.song_id, {
            "title": f"样片 {self.song_id}",
            "artist": f"只读测试媒体 {self.audio_codec}",
            "acronym": str(self.song_id),
            "language": "国语",
            "tags": ["流行"],
        })

    @property
    def title(self) -> str:
        return self.meta["title"]

    @property
    def artist(self) -> str:
        return self.meta["artist"]

    @property
    def acronym(self) -> str:
        return self.meta.get("acronym", "")

    @property
    def language(self) -> str:
        return self.meta.get("language", "国语")

    @property
    def tags(self) -> list[str]:
        return self.meta.get("tags", [])

    def song_dto(self) -> dict[str, Any]:
        return {
            "id": self.song_id,
            "title": self.title,
            "artist": self.artist,
            "artistGender": "",
            "mediaType": "MV",
            "hasVocalTrack": True,
            "durationMs": self.duration_ms,
            "lyricType": "none",
            "coverUrl": None,
            "playCount": 0,
            "artistAvatarUrl": None,
            "catalogNumber": str(self.song_id),
            "playable": True,
            "unavailableReason": None,
            "resolution": self.resolution,
            "language": self.language,
            "tags": self.tags,
            "acronym": self.acronym,
        }

    def file_source(self) -> dict[str, Any]:
        return {
            "id": self.file_id,
            "format": "mkv",
            "audioTracks": 2,
            "vocalTrackIndex": 1,
            "vocalConfidence": "fixture-only-index-mapping",
            "resolution": self.resolution,
            "priority": 1,
            "ready": True,
            "audioLayout": {
                "layout": "DUAL_TRACK",
                "originalTrackIndex": 0,
                "accompanimentTrackIndex": 1,
                "originalChannel": "LEFT",
                "accompanimentChannel": "RIGHT",
            },
        }


def resolve_samples(root: Path = SAMPLE_ROOT) -> dict[int, Sample]:
    expected_root = SAMPLE_ROOT.resolve(strict=True)
    supplied_root = root.resolve(strict=True)
    if supplied_root != expected_root:
        raise ValueError(f"sample root must be the approved directory: {expected_root}")
    resolved: dict[int, Sample] = {}
    for index, (song_id_text, relative, duration_ms, resolution, audio_codec) in enumerate(SAMPLES, start=1):
        song_id = int(song_id_text)
        file_id = 700_000 + index
        candidate = root.joinpath(*relative.split("/"))
        cursor = root
        for part in relative.split("/"):
            cursor = cursor / part
            if cursor.is_symlink():
                raise ValueError(f"symlink is forbidden in sample path: {relative}")
        path = candidate.resolve(strict=True)
        if not path.is_relative_to(expected_root):
            raise ValueError(f"sample path escaped approved directory: {relative}")
        mode = path.stat().st_mode
        if not stat.S_ISREG(mode):
            raise ValueError(f"sample is not a regular file: {relative}")
        resolved[song_id] = Sample(
            song_id, file_id, relative, duration_ms, resolution, audio_codec, path, path.stat().st_size
        )
    if set(resolved) != SONG_IDS or set(SONG_TO_FILE) != SONG_IDS:
        raise AssertionError("whitelist is inconsistent")
    return resolved


class Fixture:
    def __init__(self, sample_root: Path, event_log: Path):
        self.samples = resolve_samples(sample_root)
        self.samples_by_file = {sample.file_id: sample for sample in self.samples.values()}
        self.event_log = event_log
        self.lock = asyncio.Lock()
        self.websockets: set[web.WebSocketResponse] = set()
        self.queue_id = 1000
        self.revision = 1
        self.seek_sequence = 0
        self.playing: dict[str, Any] | None = None
        self.waiting: list[dict[str, Any]] = []
        self.state = "idle"
        self.position_ms = 0
        self.volume = 60
        self.muted = False
        self.vocal_mode = "accompaniment"
        self.route_counts: dict[str, int] = {}

    def log(self, kind: str, **fields: Any) -> None:
        self.event_log.parent.mkdir(parents=True, exist_ok=True)
        record = {"kind": kind, **fields}
        with self.event_log.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()

    def snapshot(self) -> dict[str, Any]:
        entries: list[dict[str, Any]] = []
        if self.playing:
            entries.append({**self.playing, "status": "playing"})
        entries.extend({**item, "status": "waiting"} for item in self.waiting)
        sample = None
        if self.playing:
            sample = self.samples[int(self.playing["song"]["id"])]
        return {
            "playing": self.playing,
            "list": entries,
            "state": self.state,
            "volume": self.volume,
            "muted": self.muted,
            "vocalMode": self.vocal_mode,
            "audioLayout": sample.file_source()["audioLayout"] if sample else {
                "layout": "NORMAL_STEREO",
                "originalTrackIndex": None,
                "accompanimentTrackIndex": None,
                "originalChannel": "LEFT",
                "accompanimentChannel": "RIGHT",
            },
            "tvOnline": True,
            "connectedPhones": 0,
            "positionMs": self.position_ms,
            "seekSequence": self.seek_sequence,
            "stateRevision": self.revision,
            "playback": {
                "kind": "NATIVE" if sample else "NONE",
                "status": "READY" if sample else "IDLE",
                "sourceFileId": sample.file_id if sample else None,
                "variantId": None,
                "streamUrl": f"/api/stream/{sample.file_id}" if sample else None,
                "audioTracks": 2 if sample else 0,
                "vocalTrackIndex": 1 if sample else None,
                "audioLayout": sample.file_source()["audioLayout"] if sample else {"layout": "NORMAL_STEREO"},
                "errorCode": None,
                "errorMessage": None,
            },
        }

    async def publish(self, event_type: str) -> None:
        payload = {"type": event_type, "payload": self.snapshot()}
        stale: list[web.WebSocketResponse] = []
        for ws in tuple(self.websockets):
            try:
                await ws.send_json(payload)
            except (ConnectionError, RuntimeError):
                stale.append(ws)
        for ws in stale:
            self.websockets.discard(ws)

    async def json_response(self, request: web.Request, value: Any, status: int = 200) -> web.Response:
        path = request.path
        self.route_counts[path] = self.route_counts.get(path, 0) + 1
        self.log("http", method=request.method, path=path, status=status)
        return web.json_response(value, status=status)

    async def songs(self, request: web.Request) -> web.Response:
        query = (request.query.get("keyword") or request.query.get("acronym") or "").casefold().strip()
        tag = request.query.get("tag", "").strip()
        lang = request.query.get("language", "").strip()
        artist = request.query.get("artist", "").strip()
        results = list(self.samples.values())
        if tag:
            results = [s for s in results if tag in s.tags]
        if lang:
            results = [s for s in results if s.language == lang]
        if artist:
            results = [s for s in results if s.artist == artist]
        if query:
            results = [s for s in results if (
                query in str(s.song_id) or
                query in s.title.casefold() or
                query in s.artist.casefold() or
                query in s.acronym.casefold() or
                query in s.audio_codec.casefold()
            )]
        page = max(0, int(request.query.get("page", "0")))
        size = max(1, min(100, int(request.query.get("size", "50"))))
        return await self.json_response(request, [s.song_dto() for s in results[page * size:(page + 1) * size]])

    async def queue_route(self, request: web.Request) -> web.Response:
        return await self.json_response(request, self.snapshot())

    async def control(self, request: web.Request) -> web.Response:
        body = await request.json()
        action = str(body.get("action", ""))
        params = body.get("params") if isinstance(body.get("params"), dict) else {}
        async with self.lock:
            event = "queue_updated"
            if action == "order":
                song_id = int(params.get("song_id", 0))
                if song_id not in self.samples:
                    self.log("control_rejected", action=action, song_id=song_id, reason="outside-whitelist")
                    return await self.json_response(request, {"code": "SAMPLE_NOT_WHITELISTED", "message": "unknown fixture sample"}, 404)
                sample = self.samples[song_id]
                self.queue_id += 1
                entry = {
                    "queueId": self.queue_id,
                    "song": sample.song_dto(),
                    "orderedBy": 1,
                    "orderedByNick": "TV fixture",
                }
                if self.playing is None or params.get("force") is True:
                    if self.playing is not None:
                        self.waiting.insert(0, {**self.playing, "status": "waiting"})
                    self.playing = entry
                    self.state = "playing"
                    self.position_ms = 0
                    event = "now_playing"
                else:
                    self.waiting.append(entry)
                self.revision += 1
            elif action in ("play", "pause"):
                if self.playing is not None:
                    self.state = "playing" if action == "play" else "paused"
                event = "player_state"
                self.revision += 1
            elif action == "seek":
                sample = self.samples[int(self.playing["song"]["id"])] if self.playing else None
                if sample is not None:
                    requested = int(params.get("position_ms", 0))
                    self.position_ms = max(0, min(sample.duration_ms, requested))
                    self.seek_sequence += 1
                event = "playback_seeked"
                self.revision += 1
            elif action == "restart":
                self.position_ms = 0
                self.seek_sequence += 1
                event = "playback_restarted"
                self.revision += 1
            elif action == "next":
                if self.waiting:
                    self.playing = self.waiting.pop(0)
                    self.state = "playing"
                    self.position_ms = 0
                    event = "now_playing"
                else:
                    self.playing = None
                    self.state = "idle"
                    self.position_ms = 0
                    event = "now_playing"
                self.revision += 1
            elif action == "set_volume":
                self.volume = max(0, min(100, int(params.get("volume", self.volume))))
                event = "volume_changed"
                self.revision += 1
            elif action == "mute":
                self.muted = bool(params.get("muted", not self.muted))
                event = "volume_changed"
                self.revision += 1
            elif action == "set_vocal":
                self.vocal_mode = str(params.get("mode", self.vocal_mode))
                event = "vocal_changed"
                self.revision += 1
            else:
                self.log("control_rejected", action=action, reason="unsupported")
                return await self.json_response(request, {"code": "ACTION_NOT_SUPPORTED", "message": action}, 400)
            self.log("control", action=action, params=params, event=event, revision=self.revision,
                     queueId=(self.playing or {}).get("queueId"), songId=((self.playing or {}).get("song") or {}).get("id"))
            snapshot = self.snapshot()
        response = await self.json_response(request, snapshot)
        await self.publish(event)
        return response

    async def websocket(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=None, max_msg_size=1_048_576)
        await ws.prepare(request)
        self.websockets.add(ws)
        self.log("websocket_connected", path=request.path)
        try:
            await ws.send_json({"type": "player_role", "payload": {"role": "ACTIVE", "generation": 1, "lease_ms": 300_000}})
            await ws.send_json({"type": "sync_full", "payload": self.snapshot()})
            async for message in ws:
                if message.type == WSMsgType.TEXT:
                    try:
                        value = json.loads(message.data)
                    except json.JSONDecodeError:
                        self.log("websocket_invalid_json")
                        continue
                    kind = value.get("type")
                    payload = value.get("payload") if isinstance(value.get("payload"), dict) else {}
                    if kind == "ping":
                        await ws.send_json({"type": "pong", "payload": {"role": "ACTIVE", "generation": 1, "lease_ms": 300_000}})
                    elif kind == "progress":
                        self.log("progress", queueId=payload.get("queue_id"), positionMs=payload.get("position_ms"), generation=payload.get("generation"))
                    elif kind in ("finished", "play_error"):
                        self.log(kind, payload=payload)
                        await ws.send_json({"type": "playback_report_ack", "payload": {"queue_id": payload.get("queue_id", 0), "status": "accepted", "report_type": kind}})
                    else:
                        self.log("websocket_message", messageType=kind)
                elif message.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            self.websockets.discard(ws)
            self.log("websocket_closed")
        return ws

    async def dispatch(self, request: web.Request) -> web.StreamResponse:
        path = request.path.removeprefix("/api")
        if path == "/songs" and request.method == "GET":
            return await self.songs(request)
        if path == "/queue" and request.method == "GET":
            return await self.queue_route(request)
        if path == "/control" and request.method == "POST":
            return await self.control(request)
        if path == "/user" and request.method == "POST":
            body = await request.json()
            return await self.json_response(request, {"id": 1, "nickname": str(body.get("nickname") or "TV fixture")[:32]})
        if path.startswith("/songs/") and request.method == "GET":
            song_id = int(path.rsplit("/", 1)[1])
            sample = self.samples.get(song_id)
            if sample is None:
                return await self.json_response(request, {"code": "SONG_NOT_FOUND"}, 404)
            return await self.json_response(request, {
                **sample.song_dto(), "language": "", "tags": [], "lyricUrl": None,
                "files": [sample.file_source()],
            })
        if path.startswith("/playback/resolve/") and request.method == "GET":
            file_id = int(path.rsplit("/", 1)[1])
            sample = self.samples_by_file.get(file_id)
            if sample is None:
                return await self.json_response(request, {"code": "FILE_NOT_FOUND"}, 404)
            return await self.json_response(request, {
                "kind": "NATIVE", "status": "READY", "sourceFileId": file_id,
                "variantId": None, "streamUrl": f"/api/stream/{file_id}",
                "audioTracks": 2, "vocalTrackIndex": 1,
                "audioLayout": sample.file_source()["audioLayout"],
                "errorCode": None, "errorMessage": None,
            })
        if path.startswith("/stream/") and request.method in ("GET", "HEAD"):
            try:
                file_id = int(path.rsplit("/", 1)[1])
            except ValueError:
                return await self.json_response(request, {"code": "FILE_NOT_FOUND"}, 404)
            sample = self.samples_by_file.get(file_id)
            if sample is None:
                return await self.json_response(request, {"code": "FILE_NOT_FOUND"}, 404)
            self.log("stream", method=request.method, fileId=file_id, songId=sample.song_id,
                     range=request.headers.get("Range"), bytes=sample.size)
            response = web.FileResponse(sample.path, headers={"Accept-Ranges": "bytes"})
            response.content_type = "video/x-matroska"
            return response

        # Real service-shaped read-only catalog responses used only by the UI fixture.
        if path == "/ranking" and request.method == "GET":
            return await self.json_response(request, [s.song_dto() for s in self.samples.values()])
        if path == "/songs/new" and request.method == "GET":
            return await self.json_response(request, [s.song_dto() for s in self.samples.values()])
        if path == "/search" and request.method == "GET":
            return await self.songs(request)
        if path == "/browse/artists/page" and request.method == "GET":
            artists = {}
            for s in self.samples.values():
                if s.artist not in artists:
                    artists[s.artist] = {"name": s.artist, "songCount": 0, "avatarUrl": None}
                artists[s.artist]["songCount"] += 1
            items = list(artists.values())
            return await self.json_response(request, {"items": items, "total": len(items), "page": 0, "size": len(items)})
        if path == "/browse/artists/initials" and request.method == "GET":
            return await self.json_response(request, ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"])
        if path == "/browse/languages" and request.method == "GET":
            lang_counts = {}
            for s in self.samples.values():
                lang_counts[s.language] = lang_counts.get(s.language, 0) + 1
            return await self.json_response(request, [{"name": k, "count": v} for k, v in lang_counts.items()])
        if path == "/browse/tags" and request.method == "GET":
            tag_counts = {}
            for s in self.samples.values():
                for t in s.tags:
                    tag_counts[t] = tag_counts.get(t, 0) + 1
            # 补齐原版 8 大综艺标签，保证前端全匹配展示
            for p in ["中国好声音", "我是歌手", "最美和声", "中国最强音", "中国梦之声", "我为歌狂", "全能星战", "中国好歌曲"]:
                if p not in tag_counts:
                    tag_counts[p] = 1
            return await self.json_response(request, [{"name": k, "count": v} for k, v in tag_counts.items()])
        if path in ("/browse/songs/page", "/songs/page") and request.method == "GET":
            page = max(0, int(request.query.get("page", "0")))
            size = max(1, min(100, int(request.query.get("size", "50"))))
            tag = request.query.get("tag", "").strip()
            lang = request.query.get("language", "").strip()
            artist = request.query.get("artist", "").strip()
            query = (request.query.get("keyword") or request.query.get("acronym") or "").casefold().strip()
            songs = list(self.samples.values())
            if tag:
                matched = [s for s in songs if tag in s.tags]
                if not matched and tag in ["中国好声音", "我是歌手", "最美和声", "中国最强音", "中国梦之声", "我为歌狂", "全能星战", "中国好歌曲"]:
                    matched = [s for s in songs if "我是歌手" in s.tags or "全能星战" in s.tags or "流行" in s.tags][:3]
                songs = matched
            if lang:
                songs = [s for s in songs if s.language == lang]
            if artist:
                songs = [s for s in songs if s.artist == artist]
            if query:
                songs = [s for s in songs if (
                    query in str(s.song_id) or
                    query in s.title.casefold() or
                    query in s.artist.casefold() or
                    query in s.acronym.casefold()
                )]
            return await self.json_response(request, {
                "items": [s.song_dto() for s in songs[page * size:(page + 1) * size]],
                "total": len(songs), "page": page, "size": size,
            })
        if path == "/library/status" and request.method == "GET":
            return await self.json_response(request, {
                "totalSongs": len(self.samples), "libraryMode": "EXTERNAL_READ_ONLY",
                "rootState": "READY", "rootIdentityState": "VERIFIED", "countsKnown": True,
                "scanState": "READY", "phase": "READY", "discoveredFiles": 10,
                "indexedFiles": 10, "indexedSongs": 10, "readySongs": 10,
                "probePendingFiles": 0, "catalogRevision": 1, "statusRevision": 1,
                "errorCode": None, "updatedAt": "2026-11-09T00:00:00Z",
            })
        if path == "/standby/content" and request.method == "GET":
            return await self.json_response(request, {"welcomeText": "Home KTV 测试", "subtitle": "只读媒体验收", "carouselEnabled": False, "antiBurn": False, "intervalSeconds": 60, "source": "fixture", "videoScaleMode": "fit", "miniQr": False, "logoUrl": None, "songs": []})
        if path == "/release" and request.method == "GET":
            return await self.json_response(request, {"version": "fixture", "versionCode": 0, "announcement": {"enabled": False, "id": "", "title": "", "message": ""}, "tv": {"armeabiV7a": {"abi": "armeabi-v7a", "available": False, "url": "", "fileName": "", "size": 0}, "arm64V8a": {"abi": "arm64-v8a", "available": False, "url": "", "fileName": "", "size": 0}}})
        if path.startswith("/favorites") and request.method == "GET":
            return await self.json_response(request, [])
        if path.startswith("/history/") and request.method == "GET":
            return await self.json_response(request, [])
        if path == "/playlists" and request.method == "GET":
            return await self.json_response(request, [])
        if path == "/room/host" and request.method == "GET":
            return await self.json_response(request, {"claimed": False, "hostUserId": None, "hostNickname": None, "revision": 0, "isHost": False})
        if path in ("/health", "/ready") and request.method == "GET":
            return await self.json_response(request, {"service": "home-ktv", "status": "UP", "instanceId": "a1e4ee71-50a7-423b-896b-1e0f4d327a01"})
        self.log("unknown_route", method=request.method, path=path)
        return await self.json_response(request, {"code": "FIXTURE_ROUTE_NOT_IMPLEMENTED", "path": path}, 404)


def make_app(fixture: Fixture) -> web.Application:
    app = web.Application(client_max_size=64 * 1024)
    app["fixture"] = fixture
    app.router.add_get("/ws", fixture.websocket)
    app.router.add_route("*", "/api/{tail:.*}", fixture.dispatch)
    return app


async def main_async(args: argparse.Namespace) -> None:
    root = Path(args.sample_root).resolve(strict=True)
    output = Path(args.event_log).resolve()
    fixture = Fixture(root, output)
    app = make_app(fixture)
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, args.host, args.port)
    await site.start()
    sockets = site._server.sockets if site._server else []
    port = sockets[0].getsockname()[1]
    fixture.log("fixture_started", host=args.host, port=port, sampleCount=len(fixture.samples), readOnly=True)
    print(json.dumps({"host": args.host, "port": port, "sampleCount": len(fixture.samples), "eventLog": str(output)}, ensure_ascii=False), flush=True)
    try:
        await asyncio.Event().wait()
    finally:
        fixture.log("fixture_stopping", routes=fixture.route_counts)
        await runner.cleanup()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample-root", default=str(SAMPLE_ROOT))
    parser.add_argument("--event-log", required=True)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=0)
    return parser.parse_args()


if __name__ == "__main__":
    asyncio.run(main_async(parse_args()))
