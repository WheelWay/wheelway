package kopo.poly.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 사용자가 올린 장애물 제보 한 건.
 *
 * <p>정도(severity)에 따라 <b>하는 일이 완전히 다르다.</b> 같은 테이블에 있지만 경로 계산에서의
 * 취급이 셋으로 갈린다 — 설계 문서(20260805)의 2·3·4장이 그대로 여기에 대응한다.
 *
 * <pre>
 *   낮음  통행 가능  cost 에 영향 없음. 경로 화면에 참고정보로만 표시한다
 *   보통  고려       Dijkstra 를 2회 돌려 '피한 경로'와 '그냥 가는 경로'를 둘 다 주고 고르게 한다
 *   높음  통행 불가  하드필터 3번. 등록 즉시 차단에 반영하고 관리자가 사후에 확정/반려한다
 * </pre>
 *
 * <p><b>{@code edgeId} 는 채우지 않는다.</b> 컬럼은 있지만 항상 {@code null} 로 저장한다.
 * {@code EDGES.ID} 는 AUTO_INCREMENT 라 {@code OsmGraphLoader load --force} 로 재적재하면
 * 새로 매겨진다. 저장해두면 재적재 뒤 <b>엉뚱한 엣지</b>를 막게 된다. 게다가
 * {@code GraphHolder.collect()} 는 이 값이 차 있으면 좌표 매칭을 통째로 건너뛰므로,
 * 한 번 채워두면 좌표로 고칠 기회조차 사라진다. 그래서 좌표 + 반경으로만 다룬다.
 */
@Getter
@Setter
public class ObstacleReportDTO {

    // ── SEVERITY. DB 에 들어가는 값은 한글이다(DDL 주석 '낮음/보통/높음').
    //    설계 문서의 LOW / MEDIUM / HIGH 와 같은 것이다.
    public static final String SEV_LOW    = "낮음";
    public static final String SEV_MEDIUM = "보통";
    public static final String SEV_HIGH   = "높음";

    // ── STATUS.
    //    '반려'는 DDL COMMENT 에는 없지만 VARCHAR(10) 이라 그대로 들어간다.
    //    차단 조회가 STATUS IN ('대기','공개') 라서 반려하면 자동으로 차단에서 빠진다.
    public static final String ST_PENDING  = "대기";
    public static final String ST_OPEN     = "공개";
    public static final String ST_RESOLVED = "해소";
    public static final String ST_REJECTED = "반려";

    private long id;
    private String regionId;

    /** 제보자. {@code USERS} 로 FK NOT NULL 이라 <b>비로그인 제보가 불가능한 스키마</b>다. */
    private String username;

    /** 확정/반려를 처리한 관리자. */
    private String verifiedBy;

    /** 항상 {@code null} 이다. 이유는 클래스 주석 참고. */
    private Long edgeId;

    private double latitude;
    private double longitude;

    /**
     * 이 제보만의 차단 반경(m). {@code null} 이면 전역 설정({@code wheelway.block-radius-m})을 쓴다.
     *
     * <p>공사구간의 같은 이름 컬럼과 <b>목적이 다르다.</b> 공사 쪽은 지오코딩이 필지 중심에 찍어
     * 생긴 좌표 오차를 덮는 값이지만, 제보 쪽 좌표는 사람이 현장에서 직접 찍어 정확하다.
     * 여기서 조절하는 것은 오차가 아니라 <b>장애물이 실제로 차지한 크기</b>다 —
     * 볼라드 하나와 보도 전체를 막은 자재 적치를 같은 반경으로 다룰 수 없다.
     *
     * <p>사용자 화면은 미터를 직접 묻지 않고 '여기 한 곳 / 이 구간 / 넓게' 셋 중 하나를 받아
     * 5 · 15 · 30 으로 바꿔 보낸다. 어느 쪽이든 서버가 상한
     * ({@code wheelway.report-max-radius-m})을 다시 강제한다.
     */
    private Double blockRadiusM;

    /** 계단 / 턱 / 적치물 / 기타. */
    private String obstacleType;

    /** {@link #SEV_LOW} / {@link #SEV_MEDIUM} / {@link #SEV_HIGH}. */
    private String severity;

    private String description;

    /** Object Storage 주소. 고해상도 원본을 어디에 둘지 미정이라 <b>아직 채우지 않는다.</b> */
    private String photoUrl;

    /** {@link #ST_PENDING} / {@link #ST_OPEN} / {@link #ST_RESOLVED} / {@link #ST_REJECTED}. */
    private String status;

    /** '지금은 없어요' 카운트. 누가 눌렀는지는 기록하지 않아 중복 클릭을 막지 못한다. */
    private int resolvedCount;

    /** {@code yyyy-MM-dd HH:mm} 문자열. 화면에 그대로 찍으려는 것이라 날짜 연산을 하지 않는다. */
    private String reportedAt;
    private String verifiedAt;

    /**
     * 이 제보가 실제로 막는(또는 막게 될) 보도 구간의 좌표열. DB 컬럼이 아니라 서비스가 채운다.
     *
     * <p>공사구간과 같은 이유다 — 좌표 하나만 찍어두면 <b>어디가 막히는지 눈으로 확인할 수 없다.</b>
     * 반경 안에 드는 엣지를 그려주면 실제 차단 범위가 그대로 선으로 드러난다.
     *
     * <p>낮음 제보에는 채우지 않는다. 차단에 관여하지 않으니 그릴 구간이라는 개념 자체가 없다.
     *
     * <p>형식: {@code [[[위도,경도],[위도,경도]], ...]}
     */
    private List<double[][]> segments;
}
