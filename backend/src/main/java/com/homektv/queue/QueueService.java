package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;

/**
 * 队列状态机：点歌/顶歌/删歌（P1.9，详设§4.4）。
 * order_index 用分数插入，避免整列重排。
 *
 * Queue state machine for ordering, promoting, and cancelling songs (P1.9,
 * Detailed Design §4.4). Fractional order_index values avoid reordering the
 * entire column when inserting items.
 */
@Service
public class QueueService {

    public static final String WAITING = "waiting";
    public static final String PLAYING = "playing";
    public static final String DONE = "done";
    public static final String SKIPPED = "skipped";

    private static final double STEP = 1000.0;
    static final double MIN_ORDER_GAP = 1e-6;

    private final QueueItemRepository queueRepo;
    private final SongRepository songRepo;
    private final PlayerStateRepository playerRepo;
    private final SongAvailabilityPolicy availabilityPolicy;

    public QueueService(QueueItemRepository queueRepo, SongRepository songRepo,
                        PlayerStateRepository playerRepo,
                        SongAvailabilityPolicy availabilityPolicy) {
        this.queueRepo = queueRepo;
        this.songRepo = songRepo;
        this.playerRepo = playerRepo;
        this.availabilityPolicy = availabilityPolicy;
    }

    /**
     * 点歌：追加到队尾。
     * 同一首歌已在等待队列 → 抛 SONG_IN_QUEUE（携带位次）；force=true 时允许重复插入（合唱场景）。
     */
    @Transactional
    public QueueItem order(Long songId, Long userId, boolean force) {
        queueRepo.lockQueueMutation();
        Song song = songRepo.findById(songId)
                .orElseThrow(() -> new ApiException("SONG_NOT_FOUND", "歌曲不存在"));
        if ("file_missing".equals(song.getStatus())) {
            throw new ApiException("FILE_MISSING", "歌曲文件丢失，无法点播");
        }
        availabilityPolicy.requirePlayable(song);

        if (!force) {
            queueRepo.lockSongForOrder(songId);
            Optional<QueueItem> dup = queueRepo.findFirstBySongIdAndStatus(songId, WAITING);
            if (dup.isPresent()) {
                int pos = positionOf(dup.get());
                throw new ApiException("SONG_IN_QUEUE",
                        "《" + song.getTitle() + "》已在队列中，第 " + pos + " 位", pos);
            }
        }

        double tail = queueRepo.findFirstByStatusOrderByOrderIndexDesc(WAITING)
                .map(QueueItem::getOrderIndex)
                .orElseGet(this::currentOrBaseIndex);

        QueueItem item = new QueueItem();
        item.setSongId(songId);
        item.setOrderedBy(userId);
        item.setOrderIndex(tail + STEP);
        item.setStatus(WAITING);
        return queueRepo.save(item);
    }

    /**
     * 顶歌：插入到「当前播放的下一首」位置。
     * 多人同时顶歌时，后顶者排更前（插到当前 playing 之后、第一个 waiting 之前）。
     */
    @Transactional
    public QueueItem top(Long queueId) {
        queueRepo.lockQueueMutation();
        QueueItem item = queueRepo.findById(queueId)
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "队列项不存在"));
        if (!WAITING.equals(item.getStatus())) {
            throw new ApiException("INVALID_ACTION", "只能顶起等待中的歌曲");
        }

        double currentIndex = currentPlayingIndex();
        // 找当前播放之后的第一个等待项
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
        double nextIndex = nextWaitingIndex(waiting, queueId, currentIndex);
        if (!Double.isFinite(nextIndex - currentIndex)
                || nextIndex - currentIndex < 2 * MIN_ORDER_GAP) {
            rebalanceWaiting(waiting, currentIndex);
            nextIndex = nextWaitingIndex(waiting, queueId, currentIndex);
        }

        // 插到 current 与 next 的中值 → 排到最前（后顶者更前）
        double midpoint = currentIndex + (nextIndex - currentIndex) / 2.0;
        if (!Double.isFinite(midpoint) || midpoint <= currentIndex || midpoint >= nextIndex) {
            rebalanceWaiting(waiting, currentIndex);
            nextIndex = nextWaitingIndex(waiting, queueId, currentIndex);
            midpoint = currentIndex + (nextIndex - currentIndex) / 2.0;
        }
        item.setOrderIndex(midpoint);
        return queueRepo.save(item);
    }

    /**
     * 删歌：本人可删自己点的等待歌曲；权限校验由调用方（control）处理。
     *
     * Cancel a waiting song ordered by the current user; the caller performs
     * permission validation.
     */
    @Transactional
    public void cancel(Long queueId) {
        queueRepo.lockQueueMutation();
        QueueItem item = queueRepo.findById(queueId)
                .orElseThrow(() -> new ApiException("QUEUE_ITEM_NOT_FOUND", "队列项不存在"));
        if (!WAITING.equals(item.getStatus())) {
            throw new ApiException("INVALID_ACTION", "只能删除等待中的歌曲");
        }
        queueRepo.delete(item);
    }

    /** 等待队列（含歌曲信息由上层组装） */
    public List<QueueItem> waitingList() {
        return queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
    }

    public int waitingPosition(QueueItem item) {
        return positionOf(item);
    }

    /** 约束打散：尽量避免同一演唱者连续出现，当前播放项不参与重排。 */
    @Transactional
    public List<QueueItem> shuffleWaiting() {
        queueRepo.lockQueueMutation();
        List<QueueItem> items = new ArrayList<>(waitingList());
        if (items.size() < 2) return items;
        Collections.shuffle(items);
        items.sort(Comparator.comparingInt(q -> 0));
        List<QueueItem> arranged = new ArrayList<>();
        Long previousUser = null;
        while (!items.isEmpty()) {
            int pick = 0;
            for (int i = 0; i < items.size(); i++) {
                Long user = items.get(i).getOrderedBy();
                if (previousUser == null || !previousUser.equals(user)) { pick = i; break; }
            }
            QueueItem item = items.remove(pick);
            arranged.add(item);
            previousUser = item.getOrderedBy();
        }
        double index = currentPlayingIndex() + STEP;
        for (QueueItem item : arranged) {
            item.setOrderIndex(index);
            index += STEP;
        }
        return queueRepo.saveAll(arranged);
    }

    // ---- 内部工具 ----

    private int positionOf(QueueItem item) {
        List<QueueItem> waiting = queueRepo.findByStatusOrderByOrderIndexAsc(WAITING);
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().equals(item.getId())) return i + 1;
        }
        return waiting.size();
    }

    private double currentPlayingIndex() {
        PlayerState ps = playerRepo.getSingleton();
        if (ps.getCurrentQueueId() != null) {
            return queueRepo.findById(ps.getCurrentQueueId())
                    .map(QueueItem::getOrderIndex)
                    .orElse(0.0);
        }
        return 0.0;
    }

    private double currentOrBaseIndex() {
        return currentPlayingIndex();
    }

    private double nextWaitingIndex(List<QueueItem> waiting, Long queueId, double currentIndex) {
        return waiting.stream()
                .filter(q -> !q.getId().equals(queueId) && q.getOrderIndex() > currentIndex)
                .mapToDouble(QueueItem::getOrderIndex)
                .min()
                .orElse(currentIndex + 2 * STEP);
    }

    /** Reassigns the waiting rows with a fixed gap before midpoint insertion loses precision. */
    private void rebalanceWaiting(List<QueueItem> waiting, double currentIndex) {
        List<QueueItem> ordered = waiting.stream()
                .sorted(Comparator.comparingDouble(QueueItem::getOrderIndex))
                .toList();
        double index = currentIndex + STEP;
        for (QueueItem queued : ordered) {
            queued.setOrderIndex(index);
            index += STEP;
        }
        queueRepo.saveAll(ordered);
    }
}
