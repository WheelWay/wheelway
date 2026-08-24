package kopo.poly.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
import kopo.poly.dto.ConstructionZoneDTO;
import kopo.poly.dto.ManualEdgeDTO;
import kopo.poly.dto.RouteResultDTO;
import kopo.poly.graph.BlockedEdges;
import kopo.poly.graph.GraphHolder;
import kopo.poly.graph.ManualEdges;
import kopo.poly.graph.RouteGraph;
import kopo.poly.mapper.IGraphMapper;
import kopo.poly.service.IRouteService;
import kopo.poly.util.CmmUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 경로 확인 화면({@code /route.html})이 쓰는 REST 엔드포인트.
 *
 * <p>이 화면의 목적은 <b>계산된 경로를 지도 위에 눈으로 확인하는 것</b>이다.
 * 숫자로만 검증하면 "거리는 맞는데 이상하게 돌아가는" 경로를 잡아낼 수 없다.
 *
 * <p>JSP 대신 정적 HTML + REST 로 만든 이유: Spring Boot 4 에서 JSP 는 실행 가능한 jar 로
 * 패키징하면 동작하지 않는다(war 가 필요하다). 이 방식은 의존성 추가도 패키징 변경도 필요 없고,
 * 나중에 화면을 JSP 로 가든 다른 것으로 가든 이 엔드포인트는 그대로 쓸 수 있다.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RouteController {

    private final IRouteService routeService;
    private final GraphHolder graphHolder;
    private final BlockedEdges blockedEdges;
    private final IGraphMapper graphMapper;

    /** 공사 지점 하나가 반경 몇 m 안의 엣지를 막는지. 차단 Set 구성에 쓰는 값과 같아야 화면과 실제가 일치한다. */
    @Value("${wheelway.block-radius-m}")
    private double blockRadiusM;

    /** 반경 안에 보도가 없을 때 가장 가까운 구간을 찾아볼 최대 거리. GraphHolder 와 같은 값을 써야 한다. */
    @Value("${wheelway.block-fallback-max-m}")
    private double fallbackMaxM;


    /**
     * 카카오 JavaScript 키를 화면에 넘긴다.
     *
     * <p>HTML 에 키를 박아두면 그 파일이 git 에 올라간다. 키는
     * {@code credentials/api_keys.properties}(gitignore 대상)에만 두고 여기서 꺼내 준다.
     *
     * <p>JS 키는 어차피 브라우저에 노출되는 값이라 이걸로 비밀이 지켜지는 건 아니고,
     * <b>저장소에 커밋되지 않게 하는 것</b>이 목적이다. 실제 보호는 카카오 쪽
     * 플랫폼(사이트 도메인) 설정이 한다.
     */
    @GetMapping("/config")
    public Map<String, Object> config(HttpSession session,
                                      @Value("${kakao.js-key:}") String jsKey,
                                      @Value("${naver.client-id:}") String naverClientId) {

        // 로그인 상태. 빈 문자열이면 비로그인이다(Map.of 는 null 을 담지 못한다).
        //
        // 제보 등록이 USERS FK 때문에 로그인을 요구하는데, 화면이 그걸 미리 알 방법이 없어서
        // '등록을 눌러야 비로소 거절당하는' 흐름이 됐다. 눌러보기 전에 알 수 있어야 한다.
        // map.html 의 #user-slot 도 결국 이 값이 필요하다.
        Object uid = session.getAttribute("SS_USER_ID");
        String loginId = (uid == null) ? "" : String.valueOf(uid);

        // 안내 가능한 지역의 경계. 현재위치로 지도를 옮겼을 때 그곳에 데이터가 있는지
        // 화면이 스스로 판단할 수 있어야 한다. 없으면 서비스가 고장 난 것처럼 보인다.
        // region-id 를 바꾸면 값도 따라 바뀌므로 화면에 박아둘 수 없다.
        double[] b = graphHolder.getGraph().bounds();

        return Map.of(
                "kakaoJsKey", jsKey,
                "naverClientId", naverClientId,
                "regionId", graphHolder.getRegionId(),
                "nodeCount", graphHolder.getGraph().nodeCount(),
                "edgeCount", graphHolder.getGraph().edgeCount(),
                "blockedCount", blockedEdges.size(),
                "defaultRadiusM", blockRadiusM,
                "loginId", loginId,
                // [남서위도, 남서경도, 북동위도, 북동경도]. 노드가 없으면 빈 배열이다
                // (Map.of 는 null 을 담지 못한다).
                "bounds", b == null ? new double[0] : b);
    }

    /**
     * 경로 탐색. 화면에서 출발·도착을 찍으면 이걸 부른다.
     *
     * <p>{@code path} 는 {@code [[위도,경도], ...]} 라 카카오맵 폴리라인에 그대로 넘길 수 있다.
     */
    @GetMapping("/route")
    public RouteResultDTO route(@RequestParam double startLat,
                                @RequestParam double startLng,
                                @RequestParam double endLat,
                                @RequestParam double endLng) {

        RouteResultDTO res = routeService.searchRoute(startLat, startLng, endLat, endLng);

        log.info("경로 요청 ({}, {}) → ({}, {}) = {} {}m {}ms",
                startLat, startLng, endLat, endLng,
                res.getResultStatus(), Math.round(res.getDistanceM()), res.getDurationMs());

        return res;
    }

    /**
     * 공사구간. 공사명·기간과 함께 <b>실제로 막는 보도 구간의 좌표열</b>을 준다.
     *
     * <p>크롤링 원문의 '공사구간'은 {@code A ~ B} 형식이지만 적재된 60건은 전부 A 와 B 가 같은 주소라
     * 원본만으로는 선을 그릴 수 없다. 대신 차단 반경 안에 드는 엣지를 함께 내려주면
     * 화면에서 그것을 선으로 그릴 수 있고, 그 편이 <b>실제 차단 범위와 정확히 일치</b>한다.
     */
    @GetMapping("/overlay/construction")
    public List<ConstructionZoneDTO> construction() {
        List<ConstructionZoneDTO> zones = graphMapper.getConstructionZonesForDisplay(graphHolder.getRegionId());

        for (ConstructionZoneDTO z : zones) {
            double radius = (z.getBlockRadiusM() == null) ? blockRadiusM : z.getBlockRadiusM();
            z.setSegments(blockedSegments(z.getLatitude(), z.getLongitude(), radius));
        }
        return zones;
    }

    /**
     * 이 지점이 실제로 막는 보도 구간. 화면과 차단 Set 이 어긋나지 않도록
     * {@code GraphHolder} 의 매칭 규칙을 그대로 쓴다.
     */
    private List<double[][]> blockedSegments(double lat, double lng, double radius) {
        return graphHolder.matchSegments(lat, lng, radius);
    }

    /**
     * 좌표·반경을 바꾸면 무엇이 막히는지 <b>저장하지 않고</b> 미리 본다.
     *
     * <p>지오코딩이 필지·교차로 중심에 찍은 좌표를 바로잡을 때, 저장 전에 결과를 보고
     * 반경을 조절할 수 있어야 한다. 저장을 되돌리는 것보다 미리 보는 편이 낫다.
     *
     * <p><b>제보 화면도 이것을 쓴다.</b> 이름은 공사용이지만 안에서 하는 일은
     * {@code GraphHolder} 의 매칭 규칙 그 자체라 차단 사유를 가리지 않는다.
     * 제보용으로 같은 규칙을 한 벌 더 만들면 두 화면이 서로 다른 답을 내기 시작한다.
     */
    @GetMapping("/construction/preview")
    public Map<String, Object> preview(@RequestParam double lat,
                                       @RequestParam double lng,
                                       @RequestParam(required = false) Double radiusM) {

        double radius = (radiusM == null) ? blockRadiusM : radiusM;

        // 반경 안에 보도가 있었는지 먼저 확인해두면, 차선책으로 넘어갔는지 화면에 알려줄 수 있다.
        boolean exact = !graphHolder.getGraph().segmentsNear(lat, lng, radius).isEmpty();
        List<double[][]> segments = graphHolder.matchSegments(lat, lng, radius);

        // 가장 가까운 보도까지의 거리 — 지오코딩 결과가 얼마나 쓸만한지 재는 지표다.
        // 카카오/네이버 지오코딩을 비교할 때 이 값이 판단 근거가 된다.
        double nearestM = graphHolder.getGraph()
                .distanceToNearestEdgeM(lat, lng, Math.max(fallbackMaxM, 200));

        return Map.of("radiusM", radius,
                "segmentCount", segments.size(),
                "fallback", !exact && !segments.isEmpty(),
                "nearestM", nearestM,
                "segments", segments);
    }

    /**
     * 공사구간의 좌표와 차단 반경을 고친다.
     *
     * <p>저장 후 차단 Set 을 즉시 다시 계산하므로 서버를 재기동할 필요가 없다.
     * 고친 값이 경로에 어떻게 반영되는지 바로 확인할 수 있어야 하기 때문이다.
     *
     * @param radiusM {@code null} 이면 전역 설정값을 쓰겠다는 뜻이다
     */
    @PutMapping("/construction/{id}")
    public Map<String, Object> updateConstruction(@PathVariable long id,
                                                  @RequestParam double lat,
                                                  @RequestParam double lng,
                                                  @RequestParam(required = false) Double radiusM) {

        int updated = graphMapper.updateConstructionZone(
                id, graphHolder.getRegionId(), lat, lng, radiusM);

        if (updated == 0) {
            return Map.of("ok", false,
                    "message", "ID " + id + " 를 region='" + graphHolder.getRegionId() + "' 에서 찾지 못했습니다.");
        }

        int before = blockedEdges.size();
        graphHolder.reloadBlockedEdges();
        int after = blockedEdges.size();

        log.info("공사구간 {} 수정 — 좌표 ({}, {}), 반경 {}, 차단 엣지 {} → {}",
                id, lat, lng, radiusM == null ? "전역값" : radiusM + "m", before, after);

        return Map.of("ok", true, "blockedBefore", before, "blockedAfter", after);
    }

    /**
     * 표시 전용 기준선을 저장한다. 빈 값을 주면 지운다.
     *
     * <p>사람이 지도를 보고 '실제 공사는 여기'를 그려두는 메모다. 지오코딩 좌표는 필지 중심이라
     * 실제 위치와 다른데, 어느 쪽 보도인지는 데이터로 알 수 없어 자동 보정이 두 번 실패했다.
     * 그래서 사람이 본 것을 그대로 적어둔다.
     *
     * <p><b>차단 Set 을 다시 계산하지 않는다.</b> 이 값은 매칭에 들어가지 않기 때문이다 —
     * 차단은 그래프 엣지에서만 나와야 하고, 엣지가 없는 곳에 선을 그어도 막히지 않는다.
     * 좌표·반경 수정({@link #updateConstruction})과 엔드포인트를 나눈 것도 같은 이유다.
     */
    @PutMapping("/construction/{id}/note-line")
    public Map<String, Object> updateNoteLine(@PathVariable long id,
                                              @RequestParam(required = false) String line) {

        String value = (line == null || line.isBlank()) ? null : line;

        int updated = graphMapper.updateConstructionNoteLine(id, graphHolder.getRegionId(), value);
        if (updated == 0) {
            return Map.of("ok", false,
                    "message", "ID " + id + " 를 region='" + graphHolder.getRegionId() + "' 에서 찾지 못했습니다.");
        }

        log.info("공사구간 {} 기준선 {}", id, value == null ? "삭제" : "저장");
        return Map.of("ok", true, "cleared", value == null);
    }

    /**
     * 차단된 엣지의 좌표열. 하드필터가 실제로 어디를 막고 있는지 눈으로 확인하는 용도다.
     *
     * @return {@code [[[위도,경도],[위도,경도]], ...]}
     */
    @GetMapping("/overlay/blocked")
    public List<double[][]> blocked() {
        return graphHolder.getGraph().geometryOf(blockedEdges.snapshot());
    }

    /**
     * 사람이 직접 넣고 뺀 엣지 목록.
     */
    @GetMapping("/manual-edge")
    public List<ManualEdgeDTO> manualEdges() {
        return graphMapper.getManualEdges(graphHolder.getRegionId());
    }

    /**
     * 엣지를 직접 추가하거나 삭제한다.
     *
     * <p><b>좌표로 저장한다.</b> {@code EDGES.ID} 는 재적재하면 새로 매겨져 못 믿기 때문이다.
     * 저장 후 그래프를 통째로 다시 만든다 — 엣지가 바뀌면 인접리스트·연결 덩어리가 전부 달라져서
     * 부분 갱신으로는 맞출 수 없다.
     *
     * @param action {@code 추가} / {@code 추가-고정} / {@code 노드} / {@code 삭제}.
     *               {@code 추가-고정} 은 스냅 없이 찍은 자리 그대로 노드를 만들고,
     *               {@code 노드} 는 엣지 없이 그 노드 하나만 놓는다({@code to} 는 {@code from} 과 같게 받는다)
     * @param kind   추가일 때 가중치 표의 키. 비우면 {@code crossing}. {@code 노드} 는 안 쓴다
     */
    @PostMapping("/manual-edge")
    public Map<String, Object> addManualEdge(@RequestParam String action,
                                             @RequestParam double fromLat, @RequestParam double fromLng,
                                             @RequestParam double toLat, @RequestParam double toLng,
                                             @RequestParam(required = false) String kind,
                                             @RequestParam(required = false) String note) {

        if (!ManualEdgeDTO.ADD.equals(action) && !ManualEdgeDTO.ADD_EXACT.equals(action)
                && !ManualEdgeDTO.NODE.equals(action) && !ManualEdgeDTO.REMOVE.equals(action)) {
            return Map.of("ok", false,
                    "message", "action 은 '추가', '추가-고정', '노드', '삭제' 중 하나여야 합니다.");
        }

        ManualEdgeDTO dto = new ManualEdgeDTO();
        dto.setRegionId(graphHolder.getRegionId());
        dto.setAction(action);
        dto.setFromLat(fromLat);
        dto.setFromLng(fromLng);
        dto.setToLat(toLat);
        dto.setToLng(toLng);
        // 노드는 종류가 없다. 받아도 버린다 — 남겨두면 나중에 '그때 뭘로 찍었더라' 를 묻게 된다.
        dto.setEdgeKind(ManualEdgeDTO.NODE.equals(action) || kind == null || kind.isBlank()
                ? null : kind);
        dto.setNote(note == null || note.isBlank() ? null : note);

        graphMapper.insertManualEdge(dto);
        log.info("수동 엣지 {} 등록 #{}", action, dto.getId());

        return withReload(Map.of("ok", true, "id", dto.getId()), dto.getId());
    }

    /**
     * 여러 건을 <b>한 번에</b> 저장한다.
     *
     * <p><b>왜 필요한가</b>: 한 건씩 저장하면 건마다 {@link GraphHolder#load()} 가 돌아
     * 그래프를 통째로 다시 만든다(청주 기준 3.4초). 없는 보도를 열 구간 그리면 34초를
     * 기다리게 된다. 여기서는 전부 넣고 <b>마지막에 한 번만</b> 다시 만든다.
     *
     * <p>기록은 넣은 순서대로 ID 가 매겨진다. {@code ManualEdges} 가 ID 내림차순으로 적용하므로
     * 나중에 그린 것이 먼저 적용되는데, 이어그리기는 좌표가 같은 자리에서 만나 노드를
     * 다시 쓰므로(REUSE_M) 순서와 무관하게 이어진다.
     *
     * <p>하나라도 action 이 이상하면 <b>아무것도 저장하지 않는다.</b> 절반만 들어가면
     * 사용자가 무엇이 들어갔는지 알 수 없다.
     */
    @PostMapping("/manual-edge/batch")
    public Map<String, Object> addManualEdges(@RequestBody List<ManualEdgeDTO> items) {

        if (items == null || items.isEmpty()) {
            return Map.of("ok", false, "message", "저장할 것이 없습니다.");
        }

        for (ManualEdgeDTO m : items) {
            String action = CmmUtil.nvl(m.getAction());
            if (!ManualEdgeDTO.ADD.equals(action) && !ManualEdgeDTO.ADD_EXACT.equals(action)
                    && !ManualEdgeDTO.NODE.equals(action) && !ManualEdgeDTO.REMOVE.equals(action)) {
                return Map.of("ok", false,
                        "message", "action 은 '추가', '추가-고정', '노드', '삭제' 중 하나여야 합니다: " + action);
            }
        }

        List<Long> ids = new ArrayList<>(items.size());
        for (ManualEdgeDTO m : items) {
            m.setRegionId(graphHolder.getRegionId());
            // 노드는 종류가 없다. 단건 저장과 같은 규칙이다.
            if (ManualEdgeDTO.NODE.equals(m.getAction())
                    || m.getEdgeKind() == null || m.getEdgeKind().isBlank()) {
                m.setEdgeKind(null);
            }
            graphMapper.insertManualEdge(m);
            ids.add(m.getId());
        }
        log.info("수동 엣지 {}건 일괄 등록 {}", ids.size(), ids);

        return withReload(Map.of("ok", true, "ids", ids, "saved", ids.size()), ids);
    }

    /**
     * 수동 엣지 기록을 <b>여러 개 한 번에</b> 지운다.
     *
     * <p>한 건씩 지우면 건마다 그래프를 통째로 다시 만든다(청주 3.4초). 목록에서 여덟 개를
     * 골라 지우면 30초를 기다리게 된다. 여기서는 전부 지우고 <b>마지막에 한 번만</b> 만든다.
     *
     * <p>없는 ID 가 섞여 있어도 막지 않는다 — 화면 목록이 잠깐 낡았을 수 있고,
     * '이미 없다' 는 <b>지워달라는 요청의 목적이 이미 이뤄진 상태</b>다. 몇 건이 실제로
     * 지워졌는지를 {@code deleted} 로 돌려주고 화면이 그것을 말한다.
     */
    @DeleteMapping("/manual-edge")
    public Map<String, Object> removeManualEdges(@RequestParam List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of("ok", false, "message", "지울 것을 고르세요.");
        }

        int deleted = 0;
        for (long id : ids) {
            deleted += graphMapper.deleteManualEdge(id, graphHolder.getRegionId());
        }
        log.info("수동 엣지 기록 {}건 삭제 요청 → {}건 지움 {}", ids.size(), deleted, ids);

        return withReload(Map.of("ok", true, "deleted", deleted, "asked", ids.size()));
    }

    /**
     * 수동 엣지 기록 하나를 지운다.
     *
     * <p>지우는 것은 {@code MANUAL_EDGES} 의 <b>행</b>이다. 그래프는 그 기록이 없는 상태로
     * 다시 만들어지므로, 추가 기록을 지우면 그 엣지가 사라지고 <b>삭제 기록을 지우면
     * 그 엣지가 되살아난다.</b>
     */
    @DeleteMapping("/manual-edge/{id}")
    public Map<String, Object> removeManualEdge(@PathVariable long id) {
        int n = graphMapper.deleteManualEdge(id, graphHolder.getRegionId());
        if (n == 0) {
            return Map.of("ok", false, "message", "ID " + id + " 를 찾지 못했습니다.");
        }
        log.info("수동 엣지 기록 #{} 삭제", id);
        return withReload(Map.of("ok", true));
    }

    private Map<String, Object> withReload(Map<String, Object> base) {
        return withReload(base, java.util.List.of());
    }

    private Map<String, Object> withReload(Map<String, Object> base, Long justSavedId) {
        return withReload(base, justSavedId == null ? java.util.List.<Long>of()
                : java.util.List.of(justSavedId));
    }

    /**
     * 그래프를 다시 만들고, 바뀐 규모와 <b>반영 실패</b>를 응답에 실어 준다.
     *
     * <p>실패를 같이 주는 이유: 저장은 성공했는데 그래프에는 안 들어가는 경우가 실제로 있다.
     * 이미 지운 엣지를 또 지우려 했거나, 좌표가 그래프에서 멀 때다.
     * 엣지 수만 보여주면 사용자는 성공한 줄 알고 넘어간다.
     *
     * <p><b>★ 방금 저장한 것과 예전 기록을 갈라서 준다.</b> 저장할 때마다 그래프를 통째로
     * 다시 만들면서 <b>MANUAL_EDGES 전부를 다시 적용</b>하므로, {@code failed} 에는 예전 기록의
     * 실패까지 섞여 들어온다. 실제로 그런 기록이 하나 있었다 — 같은 삭제가 두 번 저장돼서
     * 뒤엣것이 <b>영원히</b> 실패했고, 그 하나 때문에 <b>그 뒤의 모든 저장이 빨간 실패로
     * 보였다.</b> 관리자는 자기가 방금 한 일이 안 먹은 줄 알고 같은 작업을 반복하게 된다.
     *
     * @param justSavedIds 방금 저장한 기록의 ID 들. 비어 있으면 판정하지 않는다
     */
    private Map<String, Object> withReload(Map<String, Object> base, List<Long> justSavedIds) {
        int beforeEdges = graphHolder.getGraph().edgeCount();
        int beforeNodes = graphHolder.getGraph().nodeCount();
        graphHolder.load();

        ManualEdges.Result manual = graphHolder.getLastManual();

        Map<String, Object> out = new java.util.HashMap<>(base);
        out.put("edgeBefore", beforeEdges);
        out.put("edgeAfter", graphHolder.getGraph().edgeCount());

        // 노드만 놓으면 엣지 수가 안 바뀐다. 노드 수까지 줘야 화면이 '아무 일도 안 일어났다' 고
        // 말하지 않는다 - 예전에 그런 오해를 부르는 문구를 실제로 띄우고 있었다.
        out.put("nodeBefore", beforeNodes);
        out.put("nodeAfter", graphHolder.getGraph().nodeCount());
        out.put("blocked", blockedEdges.size());
        out.put("failed", manual.failed());
        out.put("problems", manual.problems());

        // 방금 저장한 것들이 반영됐는가. 예전 기록의 실패와 섞이지 않는다.
        long failedNow = justSavedIds.stream().filter(manual::failedFor).count();
        out.put("appliedNow", failedNow == 0);
        out.put("failedNow", failedNow);
        return out;
    }

    /**
     * <b>화면에 보이는 범위</b>의 그래프 노드·엣지. "왜 이 길로 가지?"를 눈으로 확인하는 용도다.
     *
     * <p>전부 내려보내지 않는 이유: 노드 32,758 / 엣지 70,624 를 한 번에 그리면 어느 지도 SDK 든
     * 버벅인다. 요금과는 무관하지만(오버레이 추가는 과금 대상이 아니다) 렌더링이 멈춘다.
     * 그래서 <b>지도 bbox 로 자르고 상한을 둔다.</b> 넘치면 {@code truncated} 로 알린다.
     *
     * <p>엣지는 방향별 2개지만 <b>선은 한 번만</b> 그린다. 같은 구간을 두 번 겹쳐 그려봐야
     * 보이는 건 똑같고 개수만 2배가 된다. 양방향 중 한쪽이라도 막혀 있으면 막힌 것으로 본다.
     *
     * <p>계단은 그래프에 없으므로 여기 나오지 않는다. {@code /api/overlay/steps} 를 따로 쓴다.
     */
    @GetMapping("/overlay/graph")
    public Map<String, Object> graph(@RequestParam double minLat,
                                     @RequestParam double minLng,
                                     @RequestParam double maxLat,
                                     @RequestParam double maxLng,
                                     @RequestParam(required = false) Integer maxEdges,
                                     @RequestParam(required = false, defaultValue = "false")
                                     boolean manual) {

        // 8000 은 레벨 3(가로 약 1.5km) 한 화면이 대체로 안 잘리는 값이다.
        // 중구 그래프 밀도가 km² 당 약 3,300 엣지라 그 정도면 한 블록 단위 확인에 충분하다.
        int limit = (maxEdges == null) ? 8000 : Math.max(1, maxEdges);
        RouteGraph g = graphHolder.getGraph();
        Set<Long> blocked = blockedEdges.snapshot();

        // 사람이 직접 넣은 엣지·노드는 기본으로 감춘다. 원본 데이터가 아니라서
        // 사용자 화면에 섞이면 '지도에 있는 길'로 오해된다. 수정 모드에서만 manual=true 로 받는다.
        Set<Long> hideEdges = manual ? Set.of() : graphHolder.getLastManual().addedEdgeIds();
        Set<Long> hideNodes = manual ? Set.of() : graphHolder.getLastManual().addedNodeIds();

        List<double[]> nodes = new ArrayList<>();
        List<double[][]> walk = new ArrayList<>();
        List<double[][]> road = new ArrayList<>();
        List<double[][]> shut = new ArrayList<>();
        Set<Long> drawn = new HashSet<>();
        boolean truncated = false;

        for (int i = 0; i < g.nodeCount(); i++) {
            double lat = g.latitude(i), lon = g.longitude(i);
            boolean here = inBox(lat, lon, minLat, minLng, maxLat, maxLng);
            if (here && !hideNodes.contains(g.nodeId(i))) {
                nodes.add(new double[]{lat, lon});
            }

            for (RouteGraph.Edge e : g.outgoing(i)) {
                int j = e.toIndex();
                if (hideEdges.contains(e.edgeId())) {
                    continue;
                }
                // 한쪽 끝만 화면 안이어도 그린다. 안 그러면 경계에서 선이 끊겨 보인다.
                if (!here && !inBox(g.latitude(j), g.longitude(j), minLat, minLng, maxLat, maxLng)) {
                    continue;
                }
                long key = (long) Math.min(i, j) << 32 | Math.max(i, j);
                if (!drawn.add(key)) {
                    continue;
                }
                if (walk.size() + road.size() + shut.size() >= limit) {
                    truncated = true;
                    break;
                }
                double[][] line = {{lat, lon}, {g.latitude(j), g.longitude(j)}};
                if (isBlocked(g, i, j, blocked)) {
                    shut.add(line);
                } else {
                    // 보도와 차도를 갈라 보낸다. 화면이 색을 달리해야 '왜 저 길로 가는지'가 보인다.
                    (isWalkway(e.highway()) ? walk : road).add(line);
                }
            }
            if (truncated) {
                break;
            }
        }

        return Map.of("nodes", nodes, "walk", walk, "road", road, "blocked", shut,
                "nodeCount", nodes.size(),
                "edgeCount", walk.size() + road.size() + shut.size(),
                "walkCount", walk.size(), "roadCount", road.size(),
                "truncated", truncated, "limit", limit);
    }

    /**
     * 보도 계열인가. {@code living_street} 는 보차혼용이라 여기 넣는다 —
     * 옆에 따로 보도가 있는 도로가 아니므로 차도로 칠하면 오해를 부른다.
     *
     * <p>{@code connector} 는 {@code SidewalkConnector} 가, {@code crossing} 은
     * {@code MANUAL_EDGES} 가 만든 것이라 원본 OSM 값이 아니다.
     */
    private static boolean isWalkway(String highway) {
        return highway != null && WALKWAY.contains(highway);
    }

    private static final Set<String> WALKWAY = Set.of(
            "footway", "pedestrian", "path", "steps", "connector", "crossing", "living_street");

    private static boolean inBox(double lat, double lon,
                                 double minLat, double minLng, double maxLat, double maxLng) {
        return lat >= minLat && lat <= maxLat && lon >= minLng && lon <= maxLng;
    }

    /** 양방향 중 한쪽이라도 막혀 있으면 막힌 것으로 본다. 선은 하나뿐이라 둘을 구분해 보여줄 수 없다. */
    private static boolean isBlocked(RouteGraph g, int i, int j, Set<Long> blocked) {
        for (RouteGraph.Edge e : g.outgoing(i)) {
            if (e.toIndex() == j && blocked.contains(e.edgeId())) {
                return true;
            }
        }
        for (RouteGraph.Edge e : g.outgoing(j)) {
            if (e.toIndex() == i && blocked.contains(e.edgeId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 계단 구간의 좌표열. 그래프에는 없지만 "여기는 계단이라 못 간다"를 보여주기 위한 것이다.
     *
     * <p>DB 의 {@code GEOMETRY_JSON} 문자열을 그대로 넘긴다. 형식이 이미
     * {@code [[위도,경도],[위도,경도]]} 라 화면에서 파싱하면 된다.
     */
    @GetMapping("/overlay/steps")
    public List<String> steps() {
        return graphMapper.getStepsGeometry(graphHolder.getRegionId());
    }
}
