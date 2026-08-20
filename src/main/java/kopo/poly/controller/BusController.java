package kopo.poly.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kopo.poly.bus.BusUnavailableException;
import kopo.poly.dto.BusStopDTO;
import kopo.poly.dto.TransitResultDTO;
import kopo.poly.service.IBusService;
import kopo.poly.service.ITransitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 버스 탭이 쓰는 API.
 *
 * <p>이 화면이 답하려는 것은 하나다 — <b>"휠체어로 탈 수 있는 버스가 몇 분 뒤에 오나"</b>.
 * 그래서 도착 목록에서 저상 여부가 가장 앞에 온다. 일반차량 3분보다
 * 저상버스 11분이 사용자에게 쓸모 있는 정보다.
 *
 * <p>거르는 일(저상만 보기)은 화면이 한다. 서버가 잘라서 주면
 * '저상은 없고 일반만 온다'는 사실 자체를 사용자가 알 수 없게 된다.
 */
@Slf4j
@RestController
@RequestMapping("/api/bus")
@RequiredArgsConstructor
public class BusController {

    private final IBusService busService;

    /** 복합 경로. 버스와 길을 둘 다 알아야 해서 서비스가 따로 있다. */
    private final ITransitService transitService;

    /**
     * 이 좌표 주변 정류장.
     *
     * <p>좌표는 출발지나 지도 중심에서 온다. 위치 권한을 요구하지 않는다 —
     * 버스 탭은 '나갈 시각'을 정하는 자리라, 권한 창에 막히면 본말이 뒤집힌다.
     */
    @GetMapping("/stops")
    public Map<String, Object> stops(@RequestParam double lat, @RequestParam double lng) {
        List<BusStopDTO> stops = busService.nearbyStops(lat, lng);
        return Map.of("provider", busService.providerName(), "stops", stops);
    }

    /**
     * 지도 화면 안의 정류장. 지도에 두루 찍는 용도다.
     *
     * <p>{@code /stops} 와 다르다 — 그쪽은 '가까운 8곳'이고 이쪽은 '보이는 범위 전부'다.
     * 근접 조회는 반경이 API 안에 박혀 있어 지도를 채울 수 없어서 따로 뒀다.
     */
    @GetMapping("/stops-in")
    public Map<String, Object> stopsIn(@RequestParam double minLat, @RequestParam double minLng,
                                       @RequestParam double maxLat, @RequestParam double maxLng,
                                       @RequestParam(defaultValue = "150") int limit) {

        // 화면이 보낸 값을 그대로 믿지 않는다. 상한이 없으면 수백 개가 한 번에 나간다.
        int capped = Math.max(1, Math.min(limit, 300));
        return Map.of("stops", busService.stopsInBounds(minLat, minLng, maxLat, maxLng, capped));
    }

    /**
     * 이름으로 정류장 찾기. <b>장소 검색에 얹으려고 둔다.</b>
     *
     * <p>카카오 로컬 API 에는 버스정류장 카테고리가 없어서, 정류장 이름을 그대로 쳐도
     * 검색 결과가 한 건도 안 나온다. 그 빈자리를 우리가 받아둔 목록으로 채운다.
     *
     * <p>{@code lat}·{@code lng} 는 가까운 순으로 세울 기준이다. 없으면 이름순인데,
     * 같은 이름이 방향별로 여러 개라(보은군청입구 상·하행) 기준이 없으면
     * 어느 것이 내 쪽인지 알 수 없다. 화면은 지도 중심을 보낸다.
     */
    @GetMapping("/stops-find")
    public Map<String, Object> stopsFind(@RequestParam String q,
                                         @RequestParam(required = false) Double lat,
                                         @RequestParam(required = false) Double lng,
                                         @RequestParam(defaultValue = "5") int limit) {

        double[] near = (lat == null || lng == null) ? null : new double[] { lat, lng };

        // 화면이 보낸 값을 그대로 믿지 않는다. 자동완성에 수십 줄이 끼어들면 목록이 안 읽힌다.
        int capped = Math.max(1, Math.min(limit, 20));
        return Map.of("stops", busService.findStops(q, near, capped));
    }

    /**
     * <b>걸어가서 탈 수 있는 정류장</b>. 출발지 좌표에서 가까운 순.
     *
     * <p>{@code /stops}(가까운 정류장)와 다르다. 그쪽은 거리만 보고, 이쪽은
     * <b>저상버스가 서는 곳만</b> 본다. 가까운 정류장에 저상이 안 서면 휠체어
     * 사용자에게는 없는 정류장이나 마찬가지라, 그걸 사람이 직접 가려내야 했던 것이 문제였다.
     */
    @GetMapping("/boarding")
    public Map<String, Object> boarding(@RequestParam double lat, @RequestParam double lng,
                                        @RequestParam(defaultValue = "3") int limit) {

        int capped = Math.max(1, Math.min(limit, 10));
        return Map.of("stops", busService.boardingStops(lat, lng, capped));
    }

    /**
     * <b>복합 경로</b> — 걸어서 → 버스 → 걸어서.
     *
     * <p>버스 탭이 답해야 할 마지막 물음이다. 지금까지는 '이 정류장에 무엇이 오나'까지였고,
     * 여기서 비로소 <b>'거기까지 어떻게 가나'</b>가 된다.
     *
     * <p><b>'도보만' 안이 늘 같이 온다.</b> 기다리는 시간을 빼고 계산하면 버스가 늘
     * 이기는 것처럼 보이는데 실제로는 진다 — 짧은 거리는 걷는 쪽이 낫고, 배차가 드문
     * 지역은 더 그렇다. 버스 안만 내놓으면 그 사실이 화면에서 사라진다.
     *
     * <p><b>도보 시간을 서버가 정하지 않는다.</b> 미터만 내려보내고 분은 화면이 낸다 —
     * 그 사람의 실측 배수를 들고 있는 쪽이 화면이다. 여기서 4km/h 로 계산해 보내면
     * 같은 구간이 화면의 다른 자리와 다른 분으로 뜬다.
     */
    @GetMapping("/plan")
    public TransitResultDTO plan(@RequestParam double startLat, @RequestParam double startLng,
                                 @RequestParam double endLat, @RequestParam double endLng,
                                 @RequestParam(defaultValue = "3") int limit) {

        // 화면이 보낸 값을 그대로 믿지 않는다. 안이 서넛을 넘으면 고르는 일 자체가 일이 된다.
        int capped = Math.max(1, Math.min(limit, 5));
        return transitService.plan(startLat, startLng, endLat, endLng, capped);
    }

    /**
     * 정류장 한 곳의 도착 현황.
     *
     * <p>{@code arrivals} 가 비어 있는 것은 오류가 아니다 — 배차가 드문 시간대다.
     * 그때 {@code routes}(경유 노선)가 채워져 오므로 화면이 비지 않는다.
     */
    @GetMapping("/board")
    public IBusService.Board board(@RequestParam String stopId) {
        /*
          ★ 빈 값을 걸러야 한다. 그냥 넘기면 오류가 아니라 <b>엉뚱한 값</b>이 온다 —
          TAGO 에 nodeid 를 비워 보내면 도착정보는 0건인데 그 도시 노선 목록이 통째로 딸려와서,
          화면은 '이 정류장에 30개 노선이 선다'는 그럴듯한 거짓을 받는다.
          조용히 틀린 답보다 오류가 낫다.
        */
        if (stopId == null || stopId.isBlank()) {
            throw new BusUnavailableException("정류장을 먼저 고르세요.");
        }
        return busService.boardAt(stopId.trim());
    }

    /**
     * 부를 수 없는 상태는 <b>503 + 사람이 읽을 수 있는 이유</b>로 낸다.
     *
     * <p>200 에 빈 목록으로 답하지 않는 이유: 화면이 '이 근처에 정류장이 없다'고
     * 잘못 말하게 된다. 서울 API 가 승인 뒤에도 막혀 있는 상황이 정확히 그랬다.
     */
    @ExceptionHandler(BusUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(BusUnavailableException e) {
        log.warn("버스 정보를 쓸 수 없습니다: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", e.getMessage()));
    }
}
