package kopo.poly.dto;

import java.util.List;

/**
 * 노선 한 개의 '다음 차'. 시간표에서 나온 값이다.
 *
 * <p><b>왜 도착정보와 따로 있는가</b>: 도착정보는 지금 굴러가는 버스만 잡는다.
 * 보은 저상 5개 노선은 실시간에 아예 안 잡혀서({@link BusArrivalDTO} 참고)
 * 도착정보만으로는 <b>휠체어로 탈 수 있는 버스에 대해 영원히 아무 말도 못 한다</b>.
 * 이 값이 그 자리를 메운다.
 *
 * <p><b>★ 기점·종점 출발 시각이지 이 정류장 도착 시각이 아니다.</b>
 * 시간표는 지역명 기준(<i>보은 → 미원</i>)이고 TAGO 는 정류장 ID 기준이라
 * 둘을 잇는 열쇠가 노선번호뿐이다. 그래서 '이 정류장에 몇 시'는 이 표로 알 수 없다.
 * 화면은 반드시 <b>어디서 떠나는 시각인지를 같이</b> 적어야 한다 —
 * 기점 출발 시각을 도착 시각으로 읽으면 사용자는 이미 지나간 버스를 기다린다.
 *
 * @param routeNo         노선번호
 * @param originName      기점 이름(보은)
 * @param destName        종점 이름
 * @param nextFromOrigin  기점에서 다음에 떠나는 편 {@code HH:mm}. 오늘 끝났으면 빈 목록
 * @param nextFromDest    종점에서 다음에 떠나는 편(돌아오는 길)
 * @param runMin          편도 소요시간(분). <b>추정값이다</b> — 종점출발 − 기점출발.
 *                        모르면 {@code null} 이고, 그때 화면은 소요시간을 말하지 않는다
 * @param todayCount      오늘 다니는 편이 모두 몇 개인가. 하루 두 편짜리가 흔해서
 *                        이 수 자체가 '기다릴 만한가'를 알려준다
 * @param lowFloorRoute   원본의 <i>저상운행</i> 칸. <b>노선 단위라 '저상차가 온다'는 뜻이 아니다.</b>
 *                        한 노선에 저상차와 일반차가 섞여 다니므로, 탈 수 있는지는
 *                        차량 단위인 도착정보만이 말할 수 있다. 다만 보은 저상 노선은
 *                        실시간에 안 잡혀 그 확인 자체가 불가능해서, '저상 운행 노선'
 *                        까지는 알려주는 편이 아무 말 안 하는 것보다 낫다
 * @param note            원문에 붙어 있던 단서(<i>법주 경유</i>, <i>주말,휴일 운행</i>).
 *                        해석하지 않고 그대로 보낸다 — 우리가 못 알아본 것을 사람은 알아본다
 * @param stopOrd         이 정류장이 노선에서 몇 번째인가(1부터). 모르면 {@code null}
 * @param stopCount       그 노선의 정류장이 모두 몇 곳인가
 * @param toStopMin       <b>기점을 떠나 이 정류장까지 오는 데 몇 분</b>인가.
 *                        이 값이 있으면 화면이 '11:40 ~ 12:25 사이' 대신
 *                        <b>'약 11:58 통과'</b> 라고 말할 수 있다.
 *                        정류장 사이 좌표를 이은 <b>거리 비율</b>로 편도 소요시간을 나눈 <b>추정</b>이다 —
 *                        순번으로 나누지 않는 이유는 농어촌버스의 정류장 간격이
 *                        읍내 200m / 시골 3km 로 들쭉날쭉하기 때문이다
 * @param fromDestMin     반대 방향(종점 출발)일 때 이 정류장까지 몇 분인가.
 *                        같은 정류장이라도 오는 쪽에 따라 시각이 다르다
 */
public record BusTimetableDTO(String routeNo,
                              String originName,
                              String destName,
                              List<String> nextFromOrigin,
                              List<String> nextFromDest,
                              Integer runMin,
                              int todayCount,
                              boolean lowFloorRoute,
                              String note,
                              Integer stopOrd,
                              Integer stopCount,
                              Integer toStopMin,
                              Integer fromDestMin) {
}
