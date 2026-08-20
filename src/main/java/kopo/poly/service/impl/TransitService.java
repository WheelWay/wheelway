package kopo.poly.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import kopo.poly.dto.BusArrivalDTO;
import kopo.poly.dto.BusLinkDTO;
import kopo.poly.dto.BusRouteDTO;
import kopo.poly.dto.BusTimetableDTO;
import kopo.poly.dto.RouteResultDTO;
import kopo.poly.dto.TransitPlanDTO;
import kopo.poly.dto.TransitResultDTO;
import kopo.poly.service.IBusService;
import kopo.poly.service.IRouteService;
import kopo.poly.service.ITransitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 복합 경로를 조립한다. 재료는 전부 이미 있던 것이다 —
 * 저상 정류장(버스 서비스)과 휠체어 경로(경로 서비스).
 *
 * <p><b>이 클래스가 하는 일은 고르는 것이다.</b> 정류장 후보 5×5 에 노선까지 곱하면
 * 조합이 수십 개가 되는데, 그중 사람이 볼 만한 두세 개만 남겨야 한다.
 * 그 판단이 값싸게 끝나도록 <b>두 번에 나눠 추린다</b>:
 *
 * <pre>
 *   1차   직선거리로 어림해 순위를 매긴다        — 계산 0원
 *   2차   살아남은 것만 진짜 경로 탐색을 돌린다   — Dijkstra 한 번에 수십 ms
 * </pre>
 *
 * <p>1차를 건너뛰면 요청 하나가 Dijkstra 를 50번 부른다. 2차를 건너뛰면
 * 직선으로 200m 인데 하천을 건너야 해서 실제로는 1.5km 인 정류장을 추천하게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransitService implements ITransitService {

    private final IBusService busService;
    private final IRouteService routeService;

    /**
     * 정류장까지 걸어갈 수 있다고 볼 최대 거리(m).
     *
     * <p>넘는 후보는 아예 안 본다. 휠체어로 1.2km 는 도보 20분이 넘는 거리라,
     * 그보다 먼 정류장까지 걸어가 버스를 타는 안은 거의 언제나 그냥 걷는 것보다 나쁘다.
     * 상한이 없으면 '3km 걸어가서 버스 타기' 같은 안이 목록에 끼어든다.
     */
    @Value("${wheelway.transit-max-walk-m:1200}")
    private int maxWalkM;

    /** 양쪽 끝에서 정류장 후보를 몇 곳씩 볼지. 늘리면 직통을 찾을 확률이 오르고 계산이 는다. */
    @Value("${wheelway.transit-stop-candidates:5}")
    private int candidates;

    /** 버스 서비스에서 받아올 링크 수 상한. 후보 5×5 에 노선이 겹치면 금세 수십 개가 된다. */
    private static final int LINK_LIMIT = 40;

    /** 1차에서 살아남을 링크 수. 이만큼만 진짜 경로 탐색을 돌린다. */
    private static final int PRERANK_KEEP = 6;

    /**
     * 순위를 매길 때만 쓰는 도보 속도(m/분). 4km/h.
     *
     * <p><b>화면에 나가는 값이 아니다.</b> 안내에 쓰는 분은 그 사람의 실측 배수로
     * 화면이 계산한다. 여기서는 여러 안을 같은 자로 재기만 하면 되고,
     * 개인 배수는 모든 안에 똑같이 곱해지므로 <b>순서를 바꾸지 않는다.</b>
     */
    private static final double RANK_WALK_M_PER_MIN = 66.7;

    @Override
    public TransitResultDTO plan(double startLat, double startLng,
                                 double endLat, double endLng, int limit) {

        long began = System.currentTimeMillis();

        // ① 도보만. 버스가 되든 안 되든 이건 늘 낸다 — 비교 대상이 없으면 고를 수가 없다.
        TransitPlanDTO.Leg walkOnly = walkLeg(startLat, startLng, endLat, endLng);

        // ② 양쪽 끝에서 '걸어가서 탈 수 있는' 정류장. 저상이 서는 곳만 온다.
        List<IBusService.BoardingStop> fromStops = nearBoarding(startLat, startLng);
        List<IBusService.BoardingStop> toStops = nearBoarding(endLat, endLng);

        if (fromStops.isEmpty() || toStops.isEmpty()) {
            /*
              어느 쪽이 없는지 밝힌다. '버스로는 못 갑니다' 로 뭉뚱그리면 사용자는
              도착지를 조금 옮겨보는 것으로 될 일을 포기한다.
            */
            String where = fromStops.isEmpty() && toStops.isEmpty() ? "출발지와 도착지 근처"
                    : fromStops.isEmpty() ? "출발지 근처" : "도착지 근처";
            return new TransitResultDTO(walkOnly, List.of(),
                    where + " 걸어갈 만한 거리에 저상버스가 서는 정류장이 없습니다.");
        }

        // ③ 두 무리를 한 번에 잇는 저상 노선.
        List<BusLinkDTO> links = busService.directLinks(ids(fromStops), ids(toStops), LINK_LIMIT);
        if (links.isEmpty()) {
            return new TransitResultDTO(walkOnly, List.of(),
                    "한 번에 가는 저상 노선이 없습니다. 갈아타는 안내는 아직 없습니다.");
        }

        // ④ 1차 — 직선거리로 어림해 추린다.
        Map<String, Integer> straight = new HashMap<>();
        fromStops.forEach(b -> straight.put(b.stop().stopId(), b.stop().distanceM()));
        toStops.forEach(b -> straight.put(b.stop().stopId(), b.stop().distanceM()));

        List<BusLinkDTO> shortlist = new ArrayList<>(links);
        shortlist.removeIf(l -> l.board().stopId().equals(l.alight().stopId()));
        shortlist.sort(java.util.Comparator.comparingDouble(l -> preScore(l, straight)));

        // ⑤ 2차 — 살아남은 것만 진짜 경로로 잰다.
        Map<String, TransitPlanDTO.Leg> walkCache = new HashMap<>();
        Set<String> seenPair = new HashSet<>();
        List<Scored> scored = new ArrayList<>();

        int tried = 0;

        for (BusLinkDTO l : shortlist) {
            /*
              ★ 세는 것은 '성공한 안'이 아니라 <b>'경로 탐색을 돌린 횟수'</b>다.
              실패도 값을 치른다 — 갈 수 없는 정류장을 향한 Dijkstra 는 도중에 멈추지 못하고
              연결 덩어리를 통째로 훑는다. 성공만 세면 못 가는 후보가 줄줄이 있을 때
              요청 하나가 수십 번 탐색하게 된다.
            */
            if (tried >= PRERANK_KEEP) {
                break;
            }
            /*
              같은 (탈 곳, 내릴 곳) 쌍은 한 번만 낸다. 그 쌍을 지나는 노선이 여럿일 때
              목록이 같은 그림 서너 줄로 채워지기 때문이다. 1차에서 이미 좋은 순으로
              세워 뒀으므로 남는 것은 그중 가장 빠른 노선이다.
            */
            if (!seenPair.add(l.board().stopId() + ">" + l.alight().stopId())) {
                continue;
            }

            tried++;

            /*
              ★ 못 찾은 것도 기억한다. computeIfAbsent 는 null 을 안 담아서, 같은 정류장이
              다른 조합으로 또 나오면 그 비싼 실패 탐색을 처음부터 다시 한다.
            */
            String k1 = "s" + l.board().stopId();
            if (!walkCache.containsKey(k1)) {
                walkCache.put(k1, walkLeg(startLat, startLng,
                        l.board().latitude(), l.board().longitude()));
            }
            String k2 = "e" + l.alight().stopId();
            if (!walkCache.containsKey(k2)) {
                walkCache.put(k2, walkLeg(l.alight().latitude(), l.alight().longitude(),
                        endLat, endLng));
            }

            TransitPlanDTO.Leg w1 = walkCache.get(k1);
            TransitPlanDTO.Leg w2 = walkCache.get(k2);

            // 길이 없으면 그 안은 없는 것이다. 직선으로 가깝다고 갈 수 있는 것이 아니다.
            if (w1 == null || w2 == null) {
                continue;
            }
            // 진짜로 재보니 너무 멀어진 경우도 여기서 걸러진다(하천·철길로 돌아가는 정류장).
            if (w1.meters() > maxWalkM || w2.meters() > maxWalkM) {
                continue;
            }

            int rideMin = l.rideMin() == null ? 0 : l.rideMin();
            double total = w1.meters() / RANK_WALK_M_PER_MIN + rideMin + w2.meters() / RANK_WALK_M_PER_MIN;
            scored.add(new Scored(l, w1, w2, total));
        }

        if (scored.isEmpty()) {
            return new TransitResultDTO(walkOnly, List.of(),
                    "정류장까지 휠체어로 갈 수 있는 길을 찾지 못했습니다.");
        }

        scored.sort(java.util.Comparator.comparingDouble(Scored::minutes));

        // ⑥ 남은 것에만 '언제 오나'를 붙인다. 이 값은 정류장마다 API 를 한 번 더 보는 것이라
        //    목록 전체에 붙이면 호출이 그만큼 는다.
        List<TransitPlanDTO> plans = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(Math.max(1, limit), scored.size()))) {
            plans.add(withWaiting(s));
        }

        log.debug("복합 경로 · 링크 {} → 안 {} · {}ms",
                links.size(), plans.size(), System.currentTimeMillis() - began);

        return new TransitResultDTO(walkOnly, plans, null);
    }

    /** 1차 점수. 직선거리라 실제와 다르지만, <b>순서를 정하는 데는 충분하다.</b> */
    private static double preScore(BusLinkDTO l, Map<String, Integer> straight) {
        int a = straight.getOrDefault(l.board().stopId(), 0);
        int b = straight.getOrDefault(l.alight().stopId(), 0);
        int ride = l.rideMin() == null ? 0 : l.rideMin();
        return a / RANK_WALK_M_PER_MIN + ride + b / RANK_WALK_M_PER_MIN;
    }

    /** 1차를 통과한 안 하나. 정렬하려고 잠깐 들고 있는 값이다. */
    private record Scored(BusLinkDTO link,
                          TransitPlanDTO.Leg walk1,
                          TransitPlanDTO.Leg walk2,
                          double minutes) { }

    /**
     * '지금 오고 있는 저상차'와 시간표를 붙인다.
     *
     * <p>못 붙어도 안 자체는 그대로 낸다 — 언제 오는지 모르는 것과 갈 수 없는 것은 다르다.
     * 실제로 보은 저상 5개 노선은 실시간에 한 번도 안 잡히는데, 그렇다고 그 노선으로
     * 가는 길이 없는 것은 아니다.
     */
    private TransitPlanDTO withWaiting(Scored s) {
        BusLinkDTO l = s.link();

        Integer arriveSec = null;
        BusTimetableDTO tt = null;

        try {
            IBusService.Board board = busService.boardAt(l.board().stopId());

            for (BusArrivalDTO a : board.arrivals()) {
                /*
                  ★ 저상차만 센다. 같은 노선에 일반차와 저상차가 섞여 다니므로,
                  '872번이 3분 뒤'가 곧 '탈 수 있는 차가 3분 뒤'는 아니다.
                  일반차 시각을 올리면 나가서 못 타고 돌아오게 된다.
                */
                if (!l.routeNo().equals(a.routeNo()) || !Boolean.TRUE.equals(a.lowFloor())
                        || a.arriveSec() == null) {
                    continue;
                }
                if (arriveSec == null || a.arriveSec() < arriveSec) {
                    arriveSec = a.arriveSec();
                }
            }

            for (BusRouteDTO r : board.routes()) {
                if (l.routeNo().equals(r.routeNo()) && r.timetable() != null) {
                    tt = r.timetable();
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.debug("도착 정보를 붙이지 못했습니다 ({}번): {}", l.routeNo(), e.getMessage());
        }

        return new TransitPlanDTO(l.routeId(), l.routeNo(), l.board(), l.alight(),
                s.walk1(),
                new TransitPlanDTO.Ride(l.rideMeters(),
                        l.rideMin() == null ? 0 : l.rideMin(),
                        l.rideSource(), l.stopCount(), l.path()),
                s.walk2(), arriveSec, tt);
    }

    /** 걸어갈 만한 거리 안에 있는 저상 정류장만. */
    private List<IBusService.BoardingStop> nearBoarding(double lat, double lng) {
        List<IBusService.BoardingStop> found = busService.boardingStops(lat, lng, candidates);
        List<IBusService.BoardingStop> out = new ArrayList<>();
        for (IBusService.BoardingStop b : found) {
            if (b.stop().distanceM() <= maxWalkM) {
                out.add(b);
            }
        }
        return out;
    }

    private static Collection<String> ids(List<IBusService.BoardingStop> stops) {
        List<String> out = new ArrayList<>(stops.size());
        stops.forEach(b -> out.add(b.stop().stopId()));
        return out;
    }

    /**
     * 휠체어 경로 한 구간. 못 가면 {@code null}.
     *
     * <p>여기서 나온 거리가 화면의 도보 시간이 된다. 직선거리로 대신하지 않는 이유는
     * 그것이 이 서비스의 존재 이유이기 때문이다 — 계단과 공사를 피해 돌아가는 만큼이
     * 곧 휠체어 사용자가 실제로 겪는 거리다.
     */
    private TransitPlanDTO.Leg walkLeg(double fromLat, double fromLng, double toLat, double toLng) {
        RouteResultDTO r = routeService.searchRoute(fromLat, fromLng, toLat, toLng);
        if (!RouteResultDTO.STATUS_SUCCESS.equals(r.getResultStatus())) {
            return null;
        }
        return new TransitPlanDTO.Leg((int) Math.round(r.getDistanceM()), r.getPath());
    }
}
