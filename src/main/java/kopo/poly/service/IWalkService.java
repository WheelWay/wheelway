package kopo.poly.service;

import java.util.List;

import kopo.poly.dto.WalkRecordDTO;

/** 실제 이동 기록과, 거기서 얻는 개인 속도. */
public interface IWalkService {

    /**
     * 그 사람이 <b>그 정류장까지</b> 잰 기록(제외 표시한 것 포함). 각 건에 속도를 채워 준다.
     *
     * @param stopName {@code null} 이면 정류장을 안 고르고 잰 기록. 전체가 아니다
     */
    List<WalkRecordDTO> list(String username, String stopName);

    /**
     * 기록 한 건을 저장한다. {@code startedAt}·{@code arrivedAt} 은 {@code yyyy-MM-dd HH:mm:ss}.
     *
     * @throws IllegalArgumentException 거리가 0 이하이거나 도착이 출발보다 이를 때
     */
    WalkRecordDTO record(WalkRecordDTO dto);

    /** 평균에서 빼거나 되돌린다. 지우지 않는다 — 되돌릴 수 있어야 한다. @return 고친 행 수 */
    int setExcluded(long id, String username, boolean excluded);

    /**
     * 기록을 아주 지운다.
     *
     * <p>빼기와 나눠 둔 이유: <b>[도착] 누르는 걸 잊어 1시간이 찍힌 것은 이동 기록이 아니다.</b>
     * 서버가 기계로 거르는 것은 4시간이 넘는 것뿐이라 1시간짜리는 정상 범위로 통과해
     * 중앙값을 그대로 끌어당긴다. 그런 것은 빼두는 게 아니라 없애는 것이 맞다.
     *
     * @return 지운 행 수. 0 이면 없는 ID 이거나 남의 기록이다
     */
    int delete(long id, String username);

    /**
     * 안내에 쓸 값.
     *
     * <p><b>두 값의 묶는 단위가 다르다.</b> 이걸 섞으면 안내가 조용히 틀린다.
     * <pre>
     *   typicalMinutes  그 정류장까지만    ← 안내에 실제로 쓰는 값
     *   mPerMin         그 사람의 기록 전부 ← 재본 적 없는 구간을 어림할 때
     * </pre>
     * 시간은 구간마다 다르지만(집→A 8분, 집→B 12분), 속도는 그 사람의 성질이라
     * 어느 구간에서 얻었든 같은 통에 넣는 것이 맞다.
     *
     * @param stopName 기준 정류장. {@code null} 이면 정류장을 안 고르고 잰 기록 묶음
     */
    Speed speedOf(String username, String stopName);

    /**
     * 속도와 그 출처.
     *
     * <p><b>{@code typicalMinutes} 가 실제로 쓰이는 값이다.</b> 이 기능은 '집에서 정류장까지'
     * 같은 <b>같은 구간을 반복해서</b> 가는 것을 재는 것이라, 거리를 속도로 나누는 것보다
     * 지난번에 실제로 몇 분 걸렸는지가 훨씬 정확하다. 신호 하나, 엘리베이터 한 번까지 들어 있다.
     *
     * <p>{@code mPerMin} 은 <b>재본 적 없는 구간</b>을 위한 값이다. 거리밖에 모를 때 쓴다.
     *
     * @param mPerMin        m/분. 재본 적 없는 구간을 어림할 때
     * @param typicalMinutes 재본 구간들의 소요시간 중앙값(분). 기록이 없으면 {@code null}
     * @param personalized   개인 기록에서 나온 값인가. {@code false} 면 전역 기본값이다
     * @param recordCount    계산에 쓸 수 있었던 기록 수
     * @param minRecords     개인 값으로 넘어가는 데 필요한 기록 수
     * @param walkRatio      <b>도보 기준 대비 배수.</b> {@code 기본속도 ÷ mPerMin}.
     *                       1.5 면 도보 안내가 12분일 때 이 사람은 18분 걸린다는 뜻이다.
     *                       <b>1 보다 작을 수 있다</b> — 전동휠체어는 4km/h 를 넘는다.
     *                       기록이 모자라면 {@code null} 이다(1.0 이 아니다 — 모르는 것과
     *                       '차이가 없다'는 전혀 다르다)
     * @param ratioCount     배수를 만든 기록 수. 거리를 아는 기록만 셈에 들어간다
     * @param ratioLow       그 기록들의 배수 중 가장 작은 값. 없으면 {@code null}
     * @param ratioHigh      가장 큰 값. 없으면 {@code null}
     */
    record Speed(double mPerMin, Integer typicalMinutes,
                 boolean personalized, int recordCount, int minRecords,
                 Double walkRatio, int ratioCount, Double ratioLow, Double ratioHigh) { }
}
