package kopo.poly.dto;

/**
 * 버스 정류장 한 곳. <b>제공자가 달라도 이 모양으로 맞춰서 올린다.</b>
 *
 * <p>출처가 두 벌인 이유는 전국 하나로 되는 API 가 없기 때문이다 —
 * 서울은 TOPIS({@code ws.bus.go.kr}), 그 밖은 TAGO({@code apis.data.go.kr}) 다.
 * 클라이언트는 두 벌을 피할 수 없지만, 여기서 한 모양으로 정규화해
 * 서비스·컨트롤러·화면은 한 벌로 간다.
 *
 * @param stopId     제공자가 주는 정류장 키. TAGO 는 {@code nodeid}(BEB275000450),
 *                   서울은 {@code arsId}/{@code stId} 다. <b>도착정보를 부를 때 그대로 되돌려준다</b> —
 *                   우리가 다시 해석하지 않는다
 * @param stopName   정류장 이름. 같은 이름이 방향별로 여러 개 있는 것이 정상이다
 *                   (보은군청입구가 상·하행 두 건으로 나온다)
 * @param latitude   위도
 * @param longitude  경도
 * @param distanceM  기준 좌표에서 몇 m 인가. <b>TAGO 는 이 값을 주지 않아 우리가 계산한다.</b>
 *                   목록을 가까운 순으로 세우고 '집에서 200m' 를 보여주려고 둔다
 */
public record BusStopDTO(String stopId,
                         String stopName,
                         double latitude,
                         double longitude,
                         int distanceM) {
}
