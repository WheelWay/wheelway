package kopo.poly.dto;

/**
 * <b>여기서 타면 저기서 내릴 수 있다</b> — 저상 노선 하나가 두 정류장을 잇는 관계.
 *
 * <p>지금까지의 자료는 전부 한쪽만 봤다. {@code BusStopDTO} 는 '이 근처에 정류장이 있다',
 * {@code RouteStopDTO} 는 '이 노선이 여기를 지난다' 였다. 복합 경로가 필요로 하는 것은
 * 그 둘을 이은 <b>'A 에서 타서 B 에서 내린다'</b> 이고, 그것이 이 값이다.
 *
 * <p><b>순번이 방향을 정한다.</b> 좌표만으로는 어느 쪽으로 가는 편성인지 알 수 없다 —
 * 같은 이름의 정류장이 상·하행 두 곳이라, 순번을 안 보면 반대 방향 차를 타라는 안내가 나간다.
 * {@code ord(승차) < ord(하차)} 인 노선만 여기 담긴다.
 *
 * <p><b>환승은 담지 않는다.</b> 노선 118개를 짝지어 갈아타는 조합은 복잡도가 몇 배로 뛴다.
 * 직통이 없으면 없다고 답하는 편이, 그럴듯한 환승 안내를 지어내는 것보다 낫다.
 *
 * @param routeId    노선 키
 * @param routeNo    노선 번호. 화면이 부르는 이름이 이것이다
 * @param board      탈 정류장
 * @param alight     내릴 정류장
 * @param stopCount  몇 정거장 타는가({@code ord} 차이). 시간보다 이 값을 더 믿는 사람이 많다 —
 *                   시간은 막히면 늘지만 정거장 수는 안 변한다
 * @param rideMeters 노선을 <b>따라</b> 잰 승차~하차 거리. 직선거리가 아니다
 * @param rideMin    타고 가는 시간(분). 못 구하면 {@code null} — 지어내지 않는다
 * @param rideSource 그 시간이 어디서 왔는가. 화면이 <b>어디까지 믿을 값인지</b> 밝혀야 한다
 *                   <pre>
 *                     timetable  시간표의 편도 소요시간을 거리 비율로 나눈 값 (보은)
 *                     observed   도착정보로 잰 그 노선의 실제 주행 속도  (청주)
 *                     assumed    둘 다 없어 기본 속도로 어림한 값
 *                   </pre>
 * @param path       승차~하차 정류장을 이은 좌표열 {@code [[위도,경도], ...]}. 지도에 그릴 선이다.
 *                   <b>정류장을 직선으로 이은 것이라 실제 노선 모양이 아니다</b> —
 *                   TAGO 는 노선 선형을 주지 않는다. 정거장마다 꺾이므로 큰 왜곡은 없지만
 *                   도보 구간(그래프에서 나온 진짜 길)과 같은 굵기로 그리면 안 된다
 */
public record BusLinkDTO(String routeId,
                         String routeNo,
                         RouteStopDTO board,
                         RouteStopDTO alight,
                         int stopCount,
                         int rideMeters,
                         Integer rideMin,
                         String rideSource,
                         java.util.List<double[]> path) {
}
