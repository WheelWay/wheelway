package kopo.poly.dto;

/**
 * 정류장에 곧 도착할 버스 한 대.
 *
 * <p><b>이 기능의 핵심은 {@link #lowFloor()} 하나다.</b> 저상버스가 아니면 휠체어는
 * 애초에 탈 수 없으니, '3분 뒤 도착'은 그 버스가 저상일 때만 뜻이 있다.
 *
 * <p><b>노선 단위로는 알 수 없다.</b> 한 노선에 저상차와 일반차가 섞여 다니기 때문에
 * '저상 운행 노선' 목록으로 거르면 오지 않는 버스를 기다리게 된다.
 * 그래서 <b>차량 단위</b>로 알려주는 도착정보를 쓴다.
 *
 * @param routeId           노선 키. 노선 상세로 넘어갈 때 쓴다
 * @param routeNo           노선 번호(216). 숫자로 오기도 해서 문자열로 받는다
 * @param routeType         간선/지선/일반버스 같은 노선 종류. 그대로 보여준다
 * @param vehicleType       제공자가 준 차량 종류 <b>원문</b>. TAGO 는 {@code 저상버스}/{@code 일반차량} 이다.
 *                          가공한 값만 남기면 나중에 표기가 바뀌었을 때 원인을 못 찾는다
 * @param lowFloor          저상버스인가. <b>모르면 {@code null} 이다</b> —
 *                          {@code false} 로 채우면 '일반차량이 온다'는 거짓이 된다.
 *                          휠체어 사용자는 이 값 하나로 나갈지 말지를 정한다
 * @param arriveSec         도착까지 남은 시간(초). 제공자가 초 단위로 준다
 * @param prevStationCount  몇 정거장 앞에 있는가. 남은 시간이 갑자기 늘 때
 *                          '아직 5 정거장 전'이 사람에게는 더 믿을 만한 신호다
 */
public record BusArrivalDTO(String routeId,
                            String routeNo,
                            String routeType,
                            String vehicleType,
                            Boolean lowFloor,
                            Integer arriveSec,
                            Integer prevStationCount) {
}
