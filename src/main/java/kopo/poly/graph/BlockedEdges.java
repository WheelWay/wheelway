package kopo.poly.graph;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 차단된 엣지 ID 목록. 하드필터의 2번(공사기간)과 3번(제보 높음)이 여기로 모인다.
 *
 * <p>그래프를 다시 만들지 않고 이 Set 만 갱신하는 것이 설계의 핵심이다.
 * 제보가 하나 들어올 때마다 5만 엣지를 다시 로드하면 서비스가 멈춘다.
 *
 * <pre>
 *   // 서버 기동 시        : 공사기간 + 기존 확정 높음 제보 반영
 *   // 높음 제보 등록 즉시  : block(edgeId)      — 반영 지연 없음
 *   // 관리자 반려 시       : unblock(edgeId)    — 즉시 해제
 *   // Dijkstra 탐색 시    : if (contains(edgeId)) continue;
 * </pre>
 *
 * <p>탐색(읽기)과 제보 등록(쓰기)이 다른 스레드에서 동시에 일어나므로 {@link ConcurrentHashMap} 기반
 * Set 을 쓴다. 탐색 도중에 값이 바뀌어도 그 요청은 바뀌기 전 상태로 끝나면 되고, 다음 요청부터 반영되면 된다.
 */
@Component
public class BlockedEdges {

    private final Set<Long> blocked = ConcurrentHashMap.newKeySet();

    /** 탐색 중 O(1) 로 확인하는 지점. */
    public boolean contains(long edgeId) {
        return blocked.contains(edgeId);
    }

    public void block(long edgeId) {
        blocked.add(edgeId);
    }

    public void block(Collection<Long> edgeIds) {
        blocked.addAll(edgeIds);
    }

    /** 관리자가 오탐으로 반려했을 때. 즉시 해제된다. */
    public void unblock(long edgeId) {
        blocked.remove(edgeId);
    }

    /** 기동 시 재구성용. 통째로 갈아끼운다. */
    public void replaceAll(Collection<Long> edgeIds) {
        blocked.clear();
        blocked.addAll(edgeIds);
    }

    public int size() {
        return blocked.size();
    }

    /** 검증·디버깅용 스냅샷. 이걸 고쳐도 실제 Set 은 바뀌지 않는다. */
    public Set<Long> snapshot() {
        return Set.copyOf(blocked);
    }
}
