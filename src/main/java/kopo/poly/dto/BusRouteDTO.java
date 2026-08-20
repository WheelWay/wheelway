package kopo.poly.dto;

/**
 * 그 정류장을 지나는 노선 한 개.
 *
 * <p><b>왜 도착정보와 따로 필요한가</b>: 보은 같은 군 단위는 배차가 드물어
 * 도착정보가 <b>0건인 시간대가 대부분</b>이다. 그때 화면이 비면 사용자는
 * 기능이 고장 났다고 본다. '지금 오는 버스는 없지만 이 정류장에는 8개 노선이 선다'
 * 까지는 말해줄 수 있어야 한다.
 *
 * <p>여기에는 저상 여부가 없다. 노선 단위로는 알 수 없기 때문이다
 * ({@link BusArrivalDTO} 참고). 그래서 이 목록으로 '탈 수 있다'고 말하지 않는다.
 *
 * <p><b>운행 시간대가 여기 붙는 이유</b>: 도착정보는 <b>지금 굴러가고 있는 버스만</b> 잡는다.
 * 보은 저상 노선은 하루 몇 시간만 도는데(340번 09:55~12:55), 그 밖의 시간에는
 * 차고에 있어서 도착정보에 아예 안 나온다. 그때 화면이 말할 수 있는 건
 * '기다리면 오는가, 오늘은 끝났는가' 뿐이고, 그 답이 이 두 값이다.
 *
 * @param routeId     노선 키
 * @param routeNo     노선 번호
 * @param routeType   노선 종류(일반버스/간선 …)
 * @param startName   기점 이름
 * @param endName     종점 이름
 * @param firstTime   첫차 {@code HH:mm}. 모르면 {@code null}
 * @param lastTime    막차 {@code HH:mm}. 첫차와 같으면 <b>하루 한 편</b>이다 —
 *                    보은은 85개 노선 중 그런 것이 많다
 * @param running     지금이 운행 시간대인가. 시각을 모르면 {@code null}
 *                    ({@code false} 로 채우면 '오늘 안 다닌다'는 거짓이 된다)
 * @param timetable   시간표에서 온 '다음 차'. 그 지역 시간표가 없거나 이 번호가
 *                    표에 없으면 {@code null} 이다. <b>첫차·막차와 다른 물건이다</b> —
 *                    위 둘은 TAGO 가 준 운행 시간대(범위)고, 이건 편별 출발 시각이다.
 *                    실시간에 안 잡히는 저상 노선은 이 값이 유일한 안내가 된다
 */
public record BusRouteDTO(String routeId,
                          String routeNo,
                          String routeType,
                          String startName,
                          String endName,
                          String firstTime,
                          String lastTime,
                          Boolean running,
                          BusTimetableDTO timetable) {

    /**
     * 시간표를 아직 안 붙인 상태로 만든다.
     *
     * <p>시간표는 {@code BusService} 가 마지막에 얹는 것이라, 제공자 구현체
     * ({@code TagoBusClient})는 그런 게 있는 줄도 몰라야 한다. 이 생성자가 그 경계다.
     */
    public BusRouteDTO(String routeId, String routeNo, String routeType,
                       String startName, String endName,
                       String firstTime, String lastTime, Boolean running) {
        this(routeId, routeNo, routeType, startName, endName, firstTime, lastTime, running, null);
    }
}
