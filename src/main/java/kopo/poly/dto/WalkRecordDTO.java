package kopo.poly.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 실제로 이동한 기록 한 건. 개인 속도 학습의 원본이다.
 *
 * <p><b>왜 재는가</b>: 화면의 {@code WALK_M_PER_MIN = 66.7}(4km/h)은 비장애인 보행 속도다.
 * 수동·전동 휠체어는 값이 다르고 사람마다도 다르다. 게다가 '거리 ÷ 속도'는
 * 신호 대기·엘리베이터·경사에서 느려지는 것을 담지 못해 <b>항상 낙관적</b>이다.
 * 안내가 11분인데 실제로 20분 걸리면 늦는 것은 사용자다.
 *
 * <p>실제 걸린 시간을 재면 그 사람의 속도와 지연이 한꺼번에 들어온다.
 * 그래서 실측이 쌓이면 여유시간을 따로 더할 필요가 없다.
 *
 * <p><b>{@code ROUTE_SEARCH_LOGS} 와 다르다</b> — 그쪽 {@code DURATION_MS} 는
 * 서버가 경로를 계산하는 데 걸린 시간(ms)이지 사람이 이동한 시간이 아니다.
 */
@Getter
@Setter
public class WalkRecordDTO {

    private long id;
    private String regionId;

    /** 개인별 학습이라 반드시 있어야 한다. */
    private String username;

    /**
     * 위치를 켠 경우에만 채워진다. {@code null} 이 정상이다.
     *
     * <p>스톱워치는 <b>출발할 때 눌러두는 것</b>이라 위치 권한에 묶이면 안 된다.
     * 좌표는 나중에 '집→정류장A' 와 '집→정류장B' 를 구분하고 싶어질 때 쓸 자리다.
     */
    private Double startLat;
    private Double startLng;
    private Double endLat;
    private Double endLng;

    /**
     * 경로 탐색이 낸 거리. 좌표가 없으면 {@code null} 이다.
     *
     * <p>이 값은 {@code m/분} 을 만드는 데만 쓰인다. 그리고 그 속도는
     * <b>재본 적 없는 구간</b>을 어림할 때만 필요하다 — 늘 가는 정류장은
     * 지난번에 몇 분 걸렸는지가 훨씬 정확하다.
     */
    private Double distanceM;

    /**
     * 어느 정류장까지 잰 것인가. 위치를 안 골랐으면 {@code null} 이다.
     *
     * <p><b>중앙값을 이 단위로 묶는다.</b> 집→정류장A 8분과 집→정류장B 12분을 한 통에 섞으면
     * 중앙값이 둘 다 틀린 값이 된다. 사람은 늘 가던 정류장까지 몇 분인지를 묻는 것이지
     * '평균적인 정류장'까지 몇 분인지를 묻지 않는다.
     *
     * <p>{@code null}(정류장 미지정)도 <b>하나의 묶음</b>이다. 안 고르고 잰 기록끼리 모인다.
     * 그래서 조회는 {@code =} 가 아니라 널 안전 비교 {@code <=>} 를 쓴다.
     */
    private String stopName;

    /** {@code yyyy-MM-dd HH:mm} 문자열. 화면에 그대로 찍으려는 것이라 날짜 연산을 하지 않는다. */
    private String startedAt;
    private String arrivedAt;

    /** 실제 걸린 시간(초). SQL 의 {@code TIMESTAMPDIFF} 결과를 받는다. */
    private int elapsedSec;

    /**
     * 평균에서 뺄 기록인가({@code Y}/{@code N}).
     *
     * <p>중간에 카페를 들렀거나 [도착] 을 늦게 누른 기록을 사용자가 직접 뺀다.
     * 중앙값이 이상치에 강하긴 하지만, 티가 나는 것은 사람이 빼는 편이 정확하다.
     */
    private String excludedYn;

    private String createdAt;

    /**
     * 이 기록의 속도(m/분). DB 컬럼이 아니라 조회 시 계산해서 채운다.
     *
     * <p>컬럼으로 저장하지 않는 이유: 거리와 두 시각으로 언제든 나오는 값이라
     * 저장하면 원본과 어긋날 수 있다. 기록이 수십 건 수준이라 매번 계산해도 부담이 없다.
     */
    private Double speedMPerMin;
}
