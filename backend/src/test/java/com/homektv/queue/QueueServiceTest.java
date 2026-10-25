package com.homektv.queue;

import com.homektv.domain.PlayerState;
import com.homektv.domain.QueueItem;
import com.homektv.domain.Song;
import com.homektv.library.SongAvailabilityPolicy;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {
    @Mock private QueueItemRepository queueRepository;
    @Mock private SongRepository songRepository;
    @Mock private PlayerStateRepository playerRepository;
    @Mock private SongAvailabilityPolicy availabilityPolicy;

    private QueueService service;
    private Song song;

    @BeforeEach
    void setUp() {
        service = new QueueService(queueRepository, songRepository, playerRepository, availabilityPolicy);
        song = new Song();
        song.setId(10L);
        song.setTitle("晴天");
        song.setStatus("ready");
        PlayerState player = new PlayerState();
        player.setState("idle");
        player.setCurrentQueueId(null);

        lenient().when(queueRepository.countByStatus(QueueService.WAITING)).thenReturn(0L);
        lenient().when(songRepository.findById(10L)).thenReturn(Optional.of(song));
        lenient().when(queueRepository.findFirstByStatusOrderByOrderIndexDesc(QueueService.WAITING))
                .thenReturn(Optional.empty());
        lenient().when(playerRepository.getSingleton()).thenReturn(player);
        lenient().when(queueRepository.save(any(QueueItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(queueRepository.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void orderRejectsFullQueueBeforeLookingUpSong() {
        when(queueRepository.countByStatus(QueueService.WAITING))
                .thenReturn((long) QueueService.MAX_WAITING_ITEMS);

        assertThatThrownBy(() -> service.order(10L, 20L, true))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "QUEUE_FULL");
        verify(songRepository, never()).findById(10L);
    }

    @Test
    void orderRejectsMissingSongAndSongWithMissingFile() {
        when(songRepository.findById(11L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.order(11L, 20L, true))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "SONG_NOT_FOUND");

        Song missing = new Song();
        missing.setId(12L);
        missing.setStatus("file_missing");
        when(songRepository.findById(12L)).thenReturn(Optional.of(missing));
        assertThatThrownBy(() -> service.order(12L, 20L, true))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "FILE_MISSING");
    }

    @Test
    void orderDelegatesReadinessRejectionToTheSharedAvailabilityPolicy() {
        doThrow(new ApiException(SongAvailabilityPolicy.SONG_NOT_READY, "媒体探测未完成"))
                .when(availabilityPolicy).requirePlayable(song);

        assertThatThrownBy(() -> service.order(10L, 20L, true))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", SongAvailabilityPolicy.SONG_NOT_READY);
    }

    @Test
    void duplicateOrderReportsItsActualWaitingPosition() {
        QueueItem first = item(31L, 10L, 1000.0, QueueService.WAITING, 22L);
        QueueItem duplicate = item(32L, 10L, 2000.0, QueueService.WAITING, 23L);
        when(queueRepository.findFirstBySongIdAndStatus(10L, QueueService.WAITING))
                .thenReturn(Optional.of(duplicate));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(first, duplicate));

        assertThatThrownBy(() -> service.order(10L, 20L, false))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo("SONG_IN_QUEUE");
                    assertThat(failure.getData()).isEqualTo(2);
                });
    }

    @Test
    void forcedDuplicateUsesTheWaitingTailAndPreservesCallerIdentity() {
        QueueItem tail = item(30L, 9L, 4000.0, QueueService.WAITING, 22L);
        when(queueRepository.findFirstByStatusOrderByOrderIndexDesc(QueueService.WAITING))
                .thenReturn(Optional.of(tail));

        QueueItem ordered = service.order(10L, 27L, true);

        assertThat(ordered.getSongId()).isEqualTo(10L);
        assertThat(ordered.getOrderedBy()).isEqualTo(27L);
        assertThat(ordered.getStatus()).isEqualTo(QueueService.WAITING);
        assertThat(ordered.getOrderIndex()).isEqualTo(5000.0);
        verify(queueRepository, never()).lockSongForOrder(10L);
        verify(queueRepository, never()).findFirstBySongIdAndStatus(10L, QueueService.WAITING);
    }

    @Test
    void orderAddsANonForcedSongWhenNoDuplicateExists() {
        when(queueRepository.findFirstBySongIdAndStatus(10L, QueueService.WAITING))
                .thenReturn(Optional.empty());

        QueueItem ordered = service.order(10L, 27L, false);

        assertThat(ordered.getStatus()).isEqualTo(QueueService.WAITING);
        assertThat(ordered.getOrderIndex()).isEqualTo(1000.0);
        verify(queueRepository).lockSongForOrder(10L);
    }

    @Test
    void orderFallsBackToTheCurrentPlaybackIndexWhenWaitingQueueIsEmpty() {
        PlayerState player = new PlayerState();
        player.setCurrentQueueId(90L);
        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findById(90L))
                .thenReturn(Optional.of(item(90L, 1L, 8000.0, QueueService.PLAYING, 22L)));

        QueueItem ordered = service.order(10L, 27L, true);

        assertThat(ordered.getOrderIndex()).isEqualTo(9000.0);
    }

    @Test
    void topRejectsMissingItemsAndItemsThatAreNotWaiting() {
        when(queueRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.top(99L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "QUEUE_ITEM_NOT_FOUND");

        when(queueRepository.findById(98L))
                .thenReturn(Optional.of(item(98L, 10L, 1000.0, QueueService.PLAYING, 20L)));
        assertThatThrownBy(() -> service.top(98L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ACTION");
    }

    @Test
    void topPlacesTheWaitingItemBetweenCurrentPlaybackAndTheNextItem() {
        PlayerState player = new PlayerState();
        player.setCurrentQueueId(90L);
        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findById(90L))
                .thenReturn(Optional.of(item(90L, 1L, 1000.0, QueueService.PLAYING, 22L)));
        QueueItem belowCurrent = item(33L, 9L, 500.0, QueueService.WAITING, 25L);
        QueueItem atCurrent = item(34L, 9L, 1000.0, QueueService.WAITING, 25L);
        QueueItem next = item(32L, 9L, 2000.0, QueueService.WAITING, 24L);
        QueueItem target = item(31L, 10L, 3000.0, QueueService.WAITING, 23L);
        when(queueRepository.findById(31L)).thenReturn(Optional.of(target));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(belowCurrent, atCurrent, next, target));

        QueueItem promoted = service.top(31L);

        assertThat(promoted.getOrderIndex()).isEqualTo(1500.0);
        assertThat(promoted.getOrderIndex()).isBetween(1000.0, 2000.0);
    }

    @Test
    void topRebalancesWhenTheMidpointRoundsToEitherNeighbor() {
        assertTopRebalancesRoundedMidpoint(1.0e16);
        assertTopRebalancesRoundedMidpoint(1.0e16 + 2.0);
    }

    @Test
    void topIgnoresANonFiniteCurrentPlaybackIndex() {
        PlayerState player = new PlayerState();
        player.setCurrentQueueId(90L);
        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findById(90L))
                .thenReturn(Optional.of(item(90L, 1L, Double.POSITIVE_INFINITY, QueueService.PLAYING, 22L)));
        QueueItem next = item(32L, 9L, 2000.0, QueueService.WAITING, 24L);
        QueueItem target = item(31L, 10L, 3000.0, QueueService.WAITING, 23L);
        when(queueRepository.findById(31L)).thenReturn(Optional.of(target));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(next, target));

        QueueItem promoted = service.top(31L);

        assertThat(promoted.getOrderIndex()).isFinite().isEqualTo(1000.0);
    }

    @Test
    void topRebalancesANonFiniteWaitingIndexBeforeComputingItsMidpoint() {
        QueueItem next = item(32L, 9L, Double.POSITIVE_INFINITY, QueueService.WAITING, 24L);
        QueueItem target = item(31L, 10L, 1000.0, QueueService.WAITING, 23L);
        when(queueRepository.findById(31L)).thenReturn(Optional.of(target));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(target, next));

        QueueItem promoted = service.top(31L);

        assertThat(promoted.getOrderIndex()).isFinite().isEqualTo(1000.0);
        assertThat(next.getOrderIndex()).isEqualTo(2000.0);
    }

    @Test
    void cancelRejectsPlayingItemsAndWaitingPositionHandlesPresentAndAbsentItems() {
        QueueItem playing = item(40L, 10L, 1000.0, QueueService.PLAYING, 20L);
        when(queueRepository.findById(40L)).thenReturn(Optional.of(playing));
        assertThatThrownBy(() -> service.cancel(40L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ACTION");

        QueueItem first = item(41L, 10L, 1000.0, QueueService.WAITING, 20L);
        QueueItem second = item(42L, 11L, 2000.0, QueueService.WAITING, 21L);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(first, second));
        assertThat(service.waitingPosition(second)).isEqualTo(2);
        assertThat(service.waitingPosition(item(43L, 12L, 3000.0, QueueService.WAITING, 22L))).isEqualTo(2);
    }

    @Test
    void shuffleReturnsShortQueuesAndAvoidsAdjacentRepeatedUsers() {
        QueueItem only = item(51L, 10L, 1000.0, QueueService.WAITING, 20L);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(only));

        assertThat(service.shuffleWaiting()).containsExactly(only);
        verify(queueRepository, never()).saveAll(any());

        QueueItem first = item(61L, 10L, 1000.0, QueueService.WAITING, 20L);
        QueueItem sameUser = item(62L, 11L, 2000.0, QueueService.WAITING, 20L);
        QueueItem anotherUser = item(63L, 12L, 3000.0, QueueService.WAITING, 21L);
        QueueItem thirdUser = item(64L, 13L, 4000.0, QueueService.WAITING, 22L);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(first, sameUser, anotherUser, thirdUser));

        List<QueueItem> shuffled = service.shuffleWaiting();

        assertThat(shuffled).hasSize(4);
        assertThat(shuffled).extracting(QueueItem::getOrderIndex)
                .containsExactly(1000.0, 2000.0, 3000.0, 4000.0);
        for (int i = 1; i < shuffled.size(); i++) {
            assertThat(shuffled.get(i).getOrderedBy()).isNotEqualTo(shuffled.get(i - 1).getOrderedBy());
        }
    }

    @Test
    void shuffleAvoidsTheDeterministicGreedyTrapWhenAFeasibleArrangementExists() {
        QueueItem a1 = item(71L, 10L, 1000.0, QueueService.WAITING, 20L);
        QueueItem a2 = item(72L, 11L, 2000.0, QueueService.WAITING, 20L);
        QueueItem b = item(73L, 12L, 3000.0, QueueService.WAITING, 21L);
        QueueItem c = item(74L, 13L, 4000.0, QueueService.WAITING, 22L);
        List<QueueItem> original = List.of(a1, a2, b, c);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(original);

        QueueService deterministicService = new QueueService(
                queueRepository, songRepository, playerRepository, availabilityPolicy,
                items -> {
                    items.clear();
                    items.addAll(List.of(b, c, a1, a2));
                });
        List<QueueItem> shuffled = deterministicService.shuffleWaiting();

        assertThat(shuffled).extracting(QueueItem::getId).containsExactlyInAnyOrder(71L, 72L, 73L, 74L);
        for (int i = 1; i < shuffled.size(); i++) {
            assertThat(shuffled.get(i).getOrderedBy()).isNotEqualTo(shuffled.get(i - 1).getOrderedBy());
        }
    }

    @Test
    void shuffleMatchesTheMinimumPossibleAdjacentCountForEveryUserDistributionThroughSixItems() {
        QueueService deterministicService = new QueueService(
                queueRepository, songRepository, playerRepository, availabilityPolicy, items -> {});
        long nextId = 1000L;

        for (int total = 2; total <= 6; total++) {
            List<List<Integer>> distributions = new ArrayList<>();
            appendPositiveCompositions(total, new ArrayList<>(), distributions);
            for (List<Integer> distribution : distributions) {
                List<QueueItem> original = new ArrayList<>();
                int group = 0;
                for (int count : distribution) {
                    long orderedBy = 200L + group++;
                    for (int index = 0; index < count; index++) {
                        original.add(item(nextId++, nextId, original.size() * 1000.0,
                                QueueService.WAITING, orderedBy));
                    }
                }
                when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                        .thenReturn(original);

                List<QueueItem> shuffled = deterministicService.shuffleWaiting();

                assertThat(shuffled).extracting(QueueItem::getId)
                        .containsExactlyInAnyOrderElementsOf(original.stream().map(QueueItem::getId).toList());
                assertThat(adjacentUserCount(shuffled))
                        .as("distribution %s", distribution)
                        .isEqualTo(minimumPossibleAdjacentUserCount(original));
                assertThat(shuffled).extracting(QueueItem::getOrderIndex)
                        .containsExactlyElementsOf(java.util.stream.LongStream.range(1, total + 1)
                                .mapToDouble(index -> index * 1000.0).boxed().toList());
            }
        }
    }

    @Test
    void shuffleTreatsAnonymousOrdersAsOneGroupWithoutDroppingTheirIdentity() {
        QueueItem anonymous1 = item(81L, 10L, 1000.0, QueueService.WAITING, null);
        QueueItem anonymous2 = item(82L, 11L, 2000.0, QueueService.WAITING, null);
        QueueItem b = item(83L, 12L, 3000.0, QueueService.WAITING, 21L);
        QueueItem c = item(84L, 13L, 4000.0, QueueService.WAITING, 22L);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(anonymous1, anonymous2, b, c));
        QueueService deterministicService = new QueueService(
                queueRepository, songRepository, playerRepository, availabilityPolicy, items -> {});

        List<QueueItem> shuffled = deterministicService.shuffleWaiting();

        assertThat(shuffled).extracting(QueueItem::getId).containsExactlyInAnyOrder(81L, 82L, 83L, 84L);
        assertThat(adjacentUserCount(shuffled)).isZero();
        assertThat(shuffled.stream().filter(item -> item.getOrderedBy() == null).count()).isEqualTo(2);
    }

    @Test
    void shuffleLeavesThePlayingItemOutsideWaitingReorderAndPersistsAfterItsIndex() {
        QueueItem playing = item(90L, 1L, 5000.0, QueueService.PLAYING, 99L);
        QueueItem a1 = item(91L, 10L, 6000.0, QueueService.WAITING, 20L);
        QueueItem a2 = item(92L, 11L, 7000.0, QueueService.WAITING, 20L);
        QueueItem b = item(93L, 12L, 8000.0, QueueService.WAITING, 21L);
        QueueItem c = item(94L, 13L, 9000.0, QueueService.WAITING, 22L);
        PlayerState state = new PlayerState();
        state.setCurrentQueueId(90L);
        when(playerRepository.getSingleton()).thenReturn(state);
        when(queueRepository.findById(90L)).thenReturn(Optional.of(playing));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(a1, a2, b, c));
        QueueService deterministicService = new QueueService(
                queueRepository, songRepository, playerRepository, availabilityPolicy,
                items -> {
                    items.clear();
                    items.addAll(List.of(b, c, a1, a2));
                });

        List<QueueItem> shuffled = deterministicService.shuffleWaiting();

        assertThat(shuffled).extracting(QueueItem::getId).containsExactlyInAnyOrder(91L, 92L, 93L, 94L);
        assertThat(adjacentUserCount(shuffled)).isZero();
        assertThat(shuffled).extracting(QueueItem::getOrderIndex)
                .containsExactly(6000.0, 7000.0, 8000.0, 9000.0);
        assertThat(playing.getOrderIndex()).isEqualTo(5000.0);
        verify(queueRepository).lockQueueMutation();
    }

    @Test
    void shuffleKeepsEmptyAndSingleItemQueuesUnchanged() {
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of());
        assertThat(service.shuffleWaiting()).isEmpty();
        verify(queueRepository, never()).saveAll(any());

        QueueItem only = item(95L, 10L, 3000.0, QueueService.WAITING, 20L);
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(List.of(only));
        assertThat(service.shuffleWaiting()).containsExactly(only);
        assertThat(only.getOrderIndex()).isEqualTo(3000.0);
        verify(queueRepository, never()).saveAll(any());
    }

    @Test
    void shuffleSelectionFailsClosedWhenNoUserGroupHasRemainingItems() {
        assertThatThrownBy(() -> QueueService.selectShuffleGroup(Map.of(), null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Queue shuffle could not select a remaining user group");
    }

    private static void appendPositiveCompositions(
            int remaining, List<Integer> prefix, List<List<Integer>> output) {
        if (remaining == 0) {
            output.add(List.copyOf(prefix));
            return;
        }
        for (int next = 1; next <= remaining; next++) {
            prefix.add(next);
            appendPositiveCompositions(remaining - next, prefix, output);
            prefix.remove(prefix.size() - 1);
        }
    }

    private static int adjacentUserCount(List<QueueItem> items) {
        int count = 0;
        for (int index = 1; index < items.size(); index++) {
            if (Objects.equals(items.get(index - 1).getOrderedBy(), items.get(index).getOrderedBy())) count++;
        }
        return count;
    }

    private static int minimumPossibleAdjacentUserCount(List<QueueItem> items) {
        int largestGroup = 0;
        for (QueueItem item : items) {
            int count = (int) items.stream()
                    .filter(candidate -> Objects.equals(candidate.getOrderedBy(), item.getOrderedBy())).count();
            largestGroup = Math.max(largestGroup, count);
        }
        return Math.max(0, 2 * largestGroup - items.size() - 1);
    }

    private static QueueItem item(long id, long songId, double index, String status, Long userId) {
        QueueItem item = new QueueItem();
        item.setId(id);
        item.setSongId(songId);
        item.setOrderIndex(index);
        item.setStatus(status);
        item.setOrderedBy(userId);
        return item;
    }

    private void assertTopRebalancesRoundedMidpoint(double currentIndex) {
        PlayerState player = new PlayerState();
        player.setCurrentQueueId(90L);
        when(playerRepository.getSingleton()).thenReturn(player);
        when(queueRepository.findById(90L))
                .thenReturn(Optional.of(item(90L, 1L, currentIndex, QueueService.PLAYING, 22L)));
        QueueItem next = item(32L, 9L, currentIndex + 2.0, QueueService.WAITING, 24L);
        QueueItem target = item(31L, 10L, currentIndex + 4.0, QueueService.WAITING, 23L);
        when(queueRepository.findById(31L)).thenReturn(Optional.of(target));
        when(queueRepository.findByStatusOrderByOrderIndexAsc(QueueService.WAITING))
                .thenReturn(new ArrayList<>(List.of(next, target)));

        QueueItem promoted = service.top(31L);

        assertThat(promoted.getOrderIndex()).isGreaterThan(currentIndex);
        assertThat(promoted.getOrderIndex()).isLessThan(currentIndex + 1000.0);
    }
}
