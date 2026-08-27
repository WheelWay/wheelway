package kopo.poly.dto;

/**
 * 노선이 지나는 정류장 한 곳. <b>순번이 있는 것이 핵심이다.</b>
 *
 * <p><b>왜 따로 필요한가</b>: 지금까지 받아 온 것은 전부 <b>정류장 기준</b>이었다 —
 * '이 정류장에 340번이 선다'는 알지만 '340번이 어디어디를 지나간다'는 몰랐다.
 * 방향이 반대라 뒤집어 쓸 수도 없다(838곳을 전부 물어야 노선 하나가 나온다).
 *
 * <p>이 값이 있어야 답할 수 있는 것들:
 * <pre>
 *   버스가 이 정류장까지 오는 데 몇 분   기점출발 + 편도소요 × 여기까지 비율
 *   어느 노선이 A 에서 B 로 가는가       ord(승차) &lt; ord(하차) 인 노선
 *   몇 정거장 타는가                     ord 차이
 * </pre>
 *
 * @param ord     기점에서 몇 번째인가(1부터). TAGO {@code nodeord}.
 *                <b>이 값이 순서의 전부다</b> — 좌표만으로는 어느 쪽이 기점인지 알 수 없다
 * @param stopId  정류장 키. {@code BusStopDTO.stopId} 와 같은 체계라 그대로 맞춰볼 수 있다
 * @param stopName 정류장 이름
 * @param latitude  위도
 * @param longitude 경도
 */
public record RouteStopDTO(int ord,
                           String stopId,
                           String stopName,
                           double latitude,
                           double longitude) {
}
