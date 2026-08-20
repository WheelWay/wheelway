package kopo.poly.dto;

import java.util.List;

/**
 * <b>걸어서 → 버스 → 걸어서</b> 한 벌. 복합 경로 안내 하나가 이 모양이다.
 *
 * <p><b>왜 구간을 나눠 두는가</b>: 휠체어 사용자에게 도보와 승차는 성격이 아주 다르다.
 * 도보 구간은 그 사람 속도로 늘었다 줄었다 하지만(도보 12분이 누구에겐 18분이다)
 * <b>버스에 탄 시간은 누가 타도 같다.</b> 한 숫자로 합쳐 주면 '내가 더 걸리는 만큼'이
 * 어디인지 사라지고, 그게 이 서비스가 답해야 할 것이다.
 *
 * <p><b>★ 도보 구간에 분(分)이 없다.</b> 미터만 담는다 — 분으로 바꾸는 것은 화면의 몫이다.
 * 그 사람의 실측 배수는 화면이 들고 있고(로그인·기록에서 온다), 서버가 4km/h 로 계산해
 * 내려보내면 화면이 그걸 다시 고쳐 쓰거나 <b>두 값이 어긋난 채 나란히 뜬다.</b>
 * 값의 주인을 한 곳에 둔다.
 *
 * <p><b>기다리는 시간도 합계에 없다.</b> 그건 '지금 몇 시인가'에 달렸고, 걸어가는 동안에도
 * 흐른다. 대신 판단 재료를 그대로 준다 — 지금 오고 있는 저상차({@code arriveSec})와
 * 시간표({@code timetable}). 둘 중 하나라도 있으면 화면이 '지금 나가면 탈 수 있는가'를 말한다.
 *
 * @param routeId   노선 키
 * @param routeNo   노선 번호
 * @param board     탈 정류장
 * @param alight    내릴 정류장
 * @param walk1     출발지 → 탈 정류장. <b>휠체어 그래프로 찾은 진짜 길</b>이다(계단·공사 제외)
 * @param ride      타고 가는 구간
 * @param walk2     내릴 정류장 → 목적지. 이것도 휠체어 그래프다
 * @param arriveSec 지금 그 정류장으로 오고 있는 <b>저상</b> 차가 몇 초 뒤 도착하는가.
 *                  없으면 {@code null} — 실시간에 안 잡히는 노선이거나 지금은 안 다니는 것이다.
 *                  <b>이 값으로 '탈 수 있다'고 단정하지 않는다</b>: 걸어가는 데 걸리는 시간이
 *                  사람마다 달라서, 지금 3분 뒤 오는 차는 6분 걸리는 사람에게는 없는 차다.
 *                  그 판단은 개인 속도를 들고 있는 화면이 한다
 * @param timetable 그 노선의 시간표(있는 지역만). 실시간에 안 잡히는 저상 노선은
 *                  이것이 유일한 안내다 — 보은 저상 5개 노선이 정확히 그렇다
 */
public record TransitPlanDTO(String routeId,
                             String routeNo,
                             RouteStopDTO board,
                             RouteStopDTO alight,
                             Leg walk1,
                             Ride ride,
                             Leg walk2,
                             Integer arriveSec,
                             BusTimetableDTO timetable) {

    /**
     * 도보 한 구간.
     *
     * @param meters 휠체어 경로로 잰 거리. 직선거리가 아니다
     * @param path   지도에 그릴 좌표열 {@code [[위도,경도], ...]}
     */
    public record Leg(int meters, List<double[]> path) { }

    /**
     * 버스를 타고 가는 구간.
     *
     * @param meters    노선을 따라 잰 거리
     * @param minutes   몇 분
     * @param source    그 시간의 근거({@code timetable} · {@code observed} · {@code assumed}).
     *                  <b>화면이 밝혀 적어야 한다</b> — 어림한 값과 잰 값을 같은 얼굴로 내면 안 된다
     * @param stopCount 몇 정거장
     * @param path      정류장을 이은 좌표열. 실제 노선 선형이 아니다(TAGO 가 주지 않는다)
     */
    public record Ride(int meters, int minutes, String source, int stopCount, List<double[]> path) { }
}
