package kopo.poly.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import kopo.poly.dto.WalkRecordDTO;

/**
 * 실제 이동 기록. SQL 은 {@code resources/mapper/WalkMapper.xml} 에 있다.
 *
 * <p>속도는 컬럼이 아니라 조회할 때 계산한다. 거리와 두 시각으로 언제든 나오는 값이라
 * 저장해두면 원본과 어긋날 여지만 생긴다.
 */
@Mapper
public interface IWalkMapper {

    /**
     * 그 사람이 <b>그 정류장까지</b> 잰 기록. 최근 것이 위로 온다.
     *
     * <p><b>제외 표시한 것까지 전부 준다.</b> 화면에서 "이 기록은 뺐습니다"를 보여주고
     * 되돌릴 수 있어야 하기 때문이다. 평균 계산에서 거르는 것은 아래 메서드가 한다.
     *
     * @param stopName 정류장 이름. {@code null} 이면 <b>정류장을 안 고르고 잰 기록</b>이 나온다
     *                 (전체가 아니다). SQL 이 널 안전 비교 {@code <=>} 를 쓰는 이유가 이것이다
     */
    List<WalkRecordDTO> getRecords(@Param("username") String username,
                                   @Param("stopName") String stopName);

    /**
     * 그 사람이 잰 기록 <b>전부</b>. 정류장으로 나누지 않는다.
     *
     * <p>{@link #getRecords} 와 따로 두는 이유: 그쪽은 정류장 하나에 묶인 기록만 준다.
     * 안내에 쓰는 값이 정류장별로 나오기 때문인데, <b>속도(m/분)는 정류장을 안 가리고
     * 전부에서 낸다.</b> 그래서 '지금 안내가 왜 이 숫자인가'를 확인하려면
     * 정류장으로 나뉘지 않은 목록이 있어야 한다.
     *
     * <p>제외 표시한 것도 준다 — 무엇이 빠졌는지 보이는 것이 이 목록의 목적이다.
     */
    List<WalkRecordDTO> getAllRecords(@Param("username") String username);

    /**
     * 속도 계산에 쓸 기록만. {@code EXCLUDED_YN='N'} 이고 소요시간이 정상인 것.
     *
     * <p><b>왜 걸러야 하는가</b>: [출발] 을 누르고 바로 [도착] 을 누른 기록(0초에 가까움)이나,
     * 눌러놓고 다음 날 누른 기록이 섞이면 중앙값도 흔들린다. 사람이 지우지 않은 것 중에서도
     * 명백히 말이 안 되는 것은 기계가 거른다.
     *
     * <p>속도가 빠른 순으로 정렬해서 준다 — 서비스가 가운데 값을 집기만 하면 되도록.
     */
    List<WalkRecordDTO> getUsableRecords(@Param("username") String username,
                                         @Param("minSec") int minSec,
                                         @Param("maxSec") int maxSec);

    /** @return 채번된 {@code id} 가 dto 에 담긴다 */
    int insertRecord(WalkRecordDTO dto);

    /**
     * 기록을 평균에서 빼거나 되돌린다.
     *
     * <p>지우지 않고 표시만 하는 이유: 지우면 왜 뺐는지가 사라지고 되돌릴 수도 없다.
     *
     * @return 고친 행 수. 0 이면 그 사람의 기록이 아니다
     */
    int updateExcluded(@Param("id") long id,
                       @Param("username") String username,
                       @Param("excludedYn") String excludedYn);

    /**
     * 기록을 아주 지운다.
     *
     * <p><b>{@link #updateExcluded} 와 쓰임이 다르다.</b>
     * <pre>
     *   빼기(excluded)  진짜 이동인데 그날만 이상했다 — 편의점을 들렀다. 되돌릴 수 있어야 한다
     *   삭제(delete)    애초에 이동 기록이 아니다 — [도착] 누르는 걸 잊어 1시간이 찍혔다
     * </pre>
     * 뒤엣것은 남겨둘 이유가 없다. 목록에 계속 보이면 눈에 거슬리기만 하고,
     * 빼기로 처리하면 '뺀 기록'이 쌓여 진짜로 검토할 것을 가린다.
     *
     * <p>{@code USERNAME} 을 조건에 같이 넣는다 — ID 만으로 지우면 남의 기록을 지울 수 있다.
     *
     * @return 지운 행 수. 0 이면 없는 ID 이거나 남의 기록이다
     */
    int deleteRecord(@Param("id") long id, @Param("username") String username);
}
