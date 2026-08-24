package kopo.poly.dto;

/**
 * 한 노선을 한 시간대에 잰 주행 기록. {@code BUS_ROUTE_SPEED} 의 한 행이다.
 *
 * <p><b>왜 저장하는가</b>: 이 관측이 메모리에만 있으면 재기동할 때마다 비고, 그동안
 * 모든 노선이 {@code assumed}(상수 19km/h)로 떨어진다. 그 상수는 실측 대비 평균 27.9%
 * 틀리는 값이라(청주 61개 노선, 2026-08-22), 화면이 20분 걸릴 길을 15분이라고 말한다.
 *
 * <p><b>왜 시간대를 나누는가</b>: 같은 노선 21개를 토요일 자정과 저녁에 각각 재보니
 * 평균 22.0 → 15.5 km/h 로 움직였다(21개 중 19개가 느려졌다). 시간대를 안 나누면
 * 그 차이가 한 평균으로 뭉개져서, 자정에는 과소평가하고 저녁에는 과대평가한다.
 *
 * <p><b>속도가 아니라 거리와 시간을 담는 이유</b>: 속도를 매번 평균 내면 3초짜리 관측과
 * 300초짜리 관측이 같은 무게가 된다. 원재료를 쌓고 나눗셈은 읽을 때 한 번만 한다.
 *
 * @param regionId 지역. 노선번호가 지역마다 겹쳐서 반드시 함께 잡는다
 * @param routeNo  노선번호. {@code 40-2} 처럼 하이픈이 있어 문자열이다
 * @param bucket   시간대. {@code 0}=심야 · {@code 1}=첨두 · {@code 2}=주간.
 *                 경계는 {@code BusService.speedBucket()} 이 정한다 — 여기서 다시 정하지 않는다
 * @param meters   누적 주행 거리(m)
 * @param seconds  누적 주행 시간(초). {@code meters/seconds} 가 그 시간대의 표정속도다.
 *                 <b>얇으면 쓰지 않는다</b> — 몇 초부터 믿을지는 {@code SPEED_MIN_SEC} 가 정한다
 */
public record RouteSpeedDTO(String regionId,
                            String routeNo,
                            int bucket,
                            double meters,
                            double seconds) {
}
